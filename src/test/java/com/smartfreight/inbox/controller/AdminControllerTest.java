package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.messaging.DlqAdminService;
import com.smartfreight.inbox.messaging.TicketEventPublisher;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link AdminController}.
 *
 * Verifies:
 * – Tickets are queued for reprocessing and the response reports the count.
 * – prepareForRetry() is called before findIdsByStatusIn().
 * – When no tickets are found, a descriptive "no tickets" response is returned.
 * – Each ticket ID is re-enqueued via the outbox event publisher (Phase 1.3) —
 *   the AI pipeline itself runs in the RabbitMQ consumer, not here.
 *
 * Access control (ROLE_OPERATOR requirement) is enforced by Spring Security's
 * filter chain, not by the controller itself — covered by the SecurityConfig.
 * The standalone MockMvc here tests only controller logic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminControllerTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private TicketProcessingService ticketProcessingService;

    @Mock
    private TicketEventPublisher ticketEventPublisher;

    @Mock
    private DlqAdminService dlqAdminService;

    private MockMvc mockMvc;

    /** Tenant the standalone requests run under — S3 fail-loud: reprocess requires one. */
    private static final long TENANT = 7L;

    @BeforeEach
    void setUp() {
        AdminController controller = new AdminController(
                emailTicketRepository, ticketProcessingService, ticketEventPublisher, dlqAdminService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void reprocessAll_withTickets_returns200AndQueuesAll() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(2);
        when(emailTicketRepository.findIdsByStatusIn(any())).thenReturn(List.of(1L, 2L));

        try (var ignored = TenantContext.openAs(TENANT)) {
            mockMvc.perform(post("/admin/reprocess"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("2 tickets")));
        }

        // The re-enqueued events carry the request thread's tenant.
        verify(ticketEventPublisher).publishRetryRequested(1L, TENANT);
        verify(ticketEventPublisher).publishRetryRequested(2L, TENANT);
    }

    @Test
    void reprocessAll_withoutTenantContext_failsLoudly() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(0);
        when(emailTicketRepository.findIdsByStatusIn(any())).thenReturn(List.of(1L));

        // S3: no unset-equals-default fallback — reprocess outside a tenant scope is a bug.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mockMvc.perform(post("/admin/reprocess")))
                .hasCauseInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ticketEventPublisher);
    }

    @Test
    void reprocessAll_noTicketsToProcess_returns200WithNoTicketsMessage() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(3);
        when(emailTicketRepository.findIdsByStatusIn(any())).thenReturn(List.of());

        mockMvc.perform(post("/admin/reprocess"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No tickets to reprocess")));

        verifyNoInteractions(ticketEventPublisher);
    }

    @Test
    void reprocessAll_resetCountIncludedInResponse() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(5);
        when(emailTicketRepository.findIdsByStatusIn(any())).thenReturn(List.of());

        mockMvc.perform(post("/admin/reprocess"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("5")));
    }

    @Test
    void reprocessAll_callsPrepareForRetryBeforeFindIds() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(0);
        when(emailTicketRepository.findIdsByStatusIn(
                List.of(TicketStatus.NEW, TicketStatus.RETRY_LATER))).thenReturn(List.of());

        mockMvc.perform(post("/admin/reprocess"));

        var inOrder = inOrder(ticketProcessingService, emailTicketRepository);
        inOrder.verify(ticketProcessingService).prepareForRetry();
        inOrder.verify(emailTicketRepository).findIdsByStatusIn(any());
    }

    @Test
    void dlqStatus_reportsMessageCount() throws Exception {
        when(dlqAdminService.messageCount()).thenReturn(4L);

        mockMvc.perform(get("/admin/dlq"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("4 message(s)")));
    }

    @Test
    void dlqRequeue_defaultLimit_movesMessagesAndReportsCount() throws Exception {
        when(dlqAdminService.requeue(100)).thenReturn(2);

        mockMvc.perform(post("/admin/dlq/requeue"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Requeued 2")));

        verify(dlqAdminService).requeue(100);
    }

    @Test
    void dlqRequeue_customLimit_passedThrough() throws Exception {
        when(dlqAdminService.requeue(5)).thenReturn(5);

        mockMvc.perform(post("/admin/dlq/requeue").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Requeued 5")));

        verify(dlqAdminService).requeue(5);
    }

    @Test
    void dlqPurge_reportsPurgedCount() throws Exception {
        when(dlqAdminService.purge()).thenReturn(3);

        mockMvc.perform(post("/admin/dlq/purge"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Purged 3")));
    }
}
