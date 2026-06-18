package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.EmailReplyService;
import com.smartfreight.inbox.service.FeedbackLoopService;
import com.smartfreight.inbox.service.QuoteFollowUpService;
import com.smartfreight.inbox.service.SendRateLimiter;
import com.smartfreight.inbox.service.TicketAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for the JSON ticket API (Phase 3.2). No security/tenant filter here —
 * those are covered by KeycloakJwtRoleConverterTest / TenantFilterTest; this verifies the
 * controller's behaviour and DTO output.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketApiControllerTest {

    @Mock private EmailTicketRepository emailTicketRepository;
    @Mock private EmailReplyService emailReplyService;
    @Mock private TicketAuditService auditService;
    @Mock private SendRateLimiter sendRateLimiter;
    @Mock private QuoteFollowUpService quoteFollowUpService;
    @Mock private TenantDocumentRepository documentRepository;
    @Mock private FeedbackLoopService feedbackLoopService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TicketApiController controller = new TicketApiController(
                emailTicketRepository, emailReplyService, auditService, sendRateLimiter,
                quoteFollowUpService, documentRepository, feedbackLoopService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private EmailTicket ticket(long id, TicketStatus status) {
        return EmailTicket.builder()
                .id(id)
                .messageId("<m" + id + "@x>")
                .senderEmail("customer@x.com")
                .subject("Subject " + id)
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void listReturnsPageOfSummaries() throws Exception {
        when(emailTicketRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ticket(1, TicketStatus.DRAFT_READY)), Pageable.ofSize(50), 1));

        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].status").value("DRAFT_READY"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listWithStatusFilterQueriesByStatus() throws Exception {
        when(emailTicketRepository.findByStatus(eq(TicketStatus.FAILED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("status", "FAILED"))
                .andExpect(status().isOk());

        verify(emailTicketRepository).findByStatus(eq(TicketStatus.FAILED), any(Pageable.class));
    }

    @Test
    void detailReturnsTicket() throws Exception {
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket(1, TicketStatus.DRAFT_READY)));

        mockMvc.perform(get("/api/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.senderEmail").value("customer@x.com"));
    }

    @Test
    void detailMissingReturns404() throws Exception {
        when(emailTicketRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/tickets/99")).andExpect(status().isNotFound());
    }

    @Test
    void draftSaveSetsDraftReadyAndPersists() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.NEW);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json")
                        .content("{\"action\":\"save\",\"draftReply\":\"Hello\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<EmailTicket> saved = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getDraftReply()).isEqualTo("Hello");
    }

    @Test
    void draftSendDeliversAndCloses() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(sendRateLimiter.tryAcquire(anyLong())).thenReturn(true);

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json")
                        .content("{\"action\":\"send\",\"draftReply\":\"Reply\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        verify(emailReplyService).sendReply(t);
        verify(quoteFollowUpService).armFromTicket(t);
        verify(feedbackLoopService).captureIfEdited(t);
    }

    @Test
    void draftSendRateLimitedReturns429() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(sendRateLimiter.tryAcquire(anyLong())).thenReturn(false);
        when(sendRateLimiter.getCooldown()).thenReturn(Duration.ofSeconds(5));

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json")
                        .content("{\"action\":\"send\",\"draftReply\":\"Reply\"}"))
                .andExpect(status().isTooManyRequests());

        verify(emailReplyService, never()).sendReply(any());
    }

    @Test
    void terminalTicketDraftIsNoOp() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.AUTO_SENT);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json")
                        .content("{\"action\":\"send\",\"draftReply\":\"x\"}"))
                .andExpect(status().isOk());

        verify(emailTicketRepository, never()).save(any());
        verify(emailReplyService, never()).sendReply(any());
    }

    @Test
    void deleteSoftDeletes() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(delete("/api/tickets/1")).andExpect(status().isNoContent());

        verify(emailTicketRepository).delete(t);
        verify(auditService).record(eq(1L), eq(TicketAuditService.ACTION_DELETE), any());
    }

    @Test
    void deleteWhileProcessingReturns409() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.PROCESSING);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(delete("/api/tickets/1")).andExpect(status().isConflict());

        verify(emailTicketRepository, never()).delete(any());
    }
}
