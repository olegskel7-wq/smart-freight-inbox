package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link AdminController}.
 *
 * Verifies:
 * – Tickets are queued for reprocessing and the response reports the count.
 * – prepareForRetry() is called before findIdsByStatusIn().
 * – When no tickets are found, a descriptive "no tickets" response is returned.
 * – Each ticket ID is dispatched via the task executor.
 *
 * Access control (ROLE_OPERATOR requirement) is enforced by Spring Security's
 * filter chain, not by the controller itself — covered by the SecurityConfig.
 * The standalone MockMvc here tests only controller logic.
 *
 * TaskExecutor is stubbed to run Runnables synchronously so process() calls
 * can be verified immediately with Mockito verify().
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminControllerTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private TicketProcessingService ticketProcessingService;

    @Mock
    private TaskExecutor taskExecutor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(taskExecutor).execute(any());

        AdminController controller = new AdminController(
                emailTicketRepository, ticketProcessingService, taskExecutor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void reprocessAll_withTickets_returns200AndQueuesAll() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(2);
        when(emailTicketRepository.findIdsByStatusIn(any())).thenReturn(List.of(1L, 2L));

        mockMvc.perform(post("/admin/reprocess"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2 tickets")));

        verify(ticketProcessingService).process(1L);
        verify(ticketProcessingService).process(2L);
    }

    @Test
    void reprocessAll_noTicketsToProcess_returns200WithNoTicketsMessage() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(3);
        when(emailTicketRepository.findIdsByStatusIn(any())).thenReturn(List.of());

        mockMvc.perform(post("/admin/reprocess"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No tickets to reprocess")));

        verify(ticketProcessingService, never()).process(any());
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
}
