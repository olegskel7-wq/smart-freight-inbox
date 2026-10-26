package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.webhook.WebhookEventPublisher;
import com.smartfreight.inbox.webhook.WebhookEventType;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.EmailReplyService;
import com.smartfreight.inbox.service.FeedbackLoopService;
import com.smartfreight.inbox.service.KeycloakAdminService;
import com.smartfreight.inbox.service.QuoteFollowUpService;
import com.smartfreight.inbox.service.SendRateLimiter;
import com.smartfreight.inbox.service.TicketAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.smartfreight.inbox.entity.enums.Urgency;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.startsWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    @Mock private KeycloakAdminService keycloakAdminService;
    @Mock private WebhookEventPublisher webhookEventPublisher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TicketApiController controller = new TicketApiController(
                emailTicketRepository, emailReplyService, auditService, sendRateLimiter,
                quoteFollowUpService, documentRepository, feedbackLoopService, keycloakAdminService,
                webhookEventPublisher);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Puts a Keycloak JWT (subject = user id, optional realm roles) in the context. */
    private void authenticateAs(String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(subject)
                .claim("roles", List.of(roles))
                .claim("tenant_id", 1L)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
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
    void listDefaultSortFloatsEscalatedTicketsFirst() throws Exception {
        when(emailTicketRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(emailTicketRepository).findAll(pageable.capture());
        List<Sort.Order> orders = pageable.getValue().getSort().toList();
        // Escalation keys lead, createdAt follows.
        assertThat(orders.get(0).getProperty()).isEqualTo("sentimentNegative");
        assertThat(orders.get(0).isDescending()).isTrue();
        assertThat(orders.get(1).getProperty()).isEqualTo("urgency");
        assertThat(orders.get(1).isDescending()).isTrue();
        assertThat(orders.get(2).getProperty()).isEqualTo("createdAt");
    }

    @Test
    void listExplicitSortColumnSkipsEscalationPrepend() throws Exception {
        when(emailTicketRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("sort", "subject")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(emailTicketRepository).findAll(pageable.capture());
        List<Sort.Order> orders = pageable.getValue().getSort().toList();
        // The operator chose a column → just that, no escalation override.
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getProperty()).isEqualTo("subject");
    }

    @Test
    void listExplicitCreatedAtSortWinsOverEscalation() throws Exception {
        // Regression: clicking the Received column (sort=createdAt asc) must give strict chronological
        // order, NOT escalated-first — the default view and an explicit createdAt sort are distinct.
        when(emailTicketRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("sort", "createdAt").param("dir", "asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(emailTicketRepository).findAll(pageable.capture());
        List<Sort.Order> orders = pageable.getValue().getSort().toList();
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getProperty()).isEqualTo("createdAt");
        assertThat(orders.get(0).isAscending()).isTrue();
    }

    @Test
    void summaryDtoCarriesOneLineCappedSnippet() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setBody("Morning,\n\n  Is CMAU5551234 still held?\r\n" + "x".repeat(400));
        EmailTicket empty = ticket(2, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(t, empty), Pageable.ofSize(50), 2));

        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].snippet").value(
                        startsWith("Morning, Is CMAU5551234 still held? xxx")))
                .andExpect(jsonPath("$.content[0].snippet").value(endsWith("…")))
                .andExpect(jsonPath("$.content[0].snippet").value(hasLength(160)))
                .andExpect(jsonPath("$.content[1].snippet").doesNotExist());
    }

    @Test
    void summaryDtoCarriesUrgencyAndSentiment() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setUrgency(Urgency.HIGH);
        t.setSentimentNegative(true);
        when(emailTicketRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(t), Pageable.ofSize(50), 1));

        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].urgency").value("HIGH"))
                .andExpect(jsonPath("$.content[0].sentimentNegative").value(true));
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
    void summaryReturnsCounts() throws Exception {
        when(emailTicketRepository.countByStatusIn(any())).thenReturn(42L);
        when(emailTicketRepository.countByStatus(TicketStatus.DRAFT_READY)).thenReturn(7L);
        when(emailTicketRepository.countByStatusInAndSlaBreachedTrue(any())).thenReturn(3L);
        when(emailTicketRepository.countByStatusAndUpdatedAtGreaterThanEqual(eq(TicketStatus.AUTO_SENT), any()))
                .thenReturn(18L);

        mockMvc.perform(get("/api/tickets/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(42))
                .andExpect(jsonPath("$.awaitingReview").value(7))
                .andExpect(jsonPath("$.slaBreached").value(3))
                .andExpect(jsonPath("$.autoSentToday").value(18));
    }

    @Test
    void viewOpenDrillsDownByStatusIn() throws Exception {
        when(emailTicketRepository.findByStatusIn(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ticket(1, TicketStatus.DRAFT_READY)), Pageable.ofSize(50), 1));

        mockMvc.perform(get("/api/tickets").param("view", "open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(emailTicketRepository).findByStatusIn(any(), any(Pageable.class));
        verify(emailTicketRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void viewBreachedDrillsDownToBreachedTickets() throws Exception {
        when(emailTicketRepository.findByStatusInAndSlaBreachedTrue(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("view", "breached"))
                .andExpect(status().isOk());

        verify(emailTicketRepository).findByStatusInAndSlaBreachedTrue(any(), any(Pageable.class));
    }

    @Test
    void unknownViewFallsBackToDefaultList() throws Exception {
        when(emailTicketRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("view", "bogus"))
                .andExpect(status().isOk());

        verify(emailTicketRepository).findAll(any(Pageable.class));
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
        // Phase 3.5 S2: reply.sent fires for any sent reply; quote.sent only for RFQ (this ticket is untyped).
        verify(webhookEventPublisher).publish(eq(WebhookEventType.REPLY_SENT), any(), any());
        verify(webhookEventPublisher, never()).publish(eq(WebhookEventType.QUOTE_SENT), any(), any());
    }

    @Test
    void draftSendOfRfqEmitsQuoteWebhook() throws Exception {
        EmailTicket t = EmailTicket.builder()
                .id(1L).messageId("<m1@x>").senderEmail("customer@x.com").subject("Quote please")
                .status(TicketStatus.DRAFT_READY).ticketType(TicketType.RFQ)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(sendRateLimiter.tryAcquire(anyLong())).thenReturn(true);

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json")
                        .content("{\"action\":\"send\",\"draftReply\":\"Our price is £900\"}"))
                .andExpect(status().isOk());

        // A sent RFQ reply is both a reply and a quote — two independent events.
        verify(webhookEventPublisher).publish(eq(WebhookEventType.REPLY_SENT), any(), any());
        verify(webhookEventPublisher).publish(eq(WebhookEventType.QUOTE_SENT), any(), any());
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

    // ---- Phase 3.3 S2: shared-queue assignment ----

    @Test
    void listMineFilterQueriesByCaller() throws Exception {
        authenticateAs("uuid-me");
        when(emailTicketRepository.findByAssignee(eq("uuid-me"), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("assignee", "mine"))
                .andExpect(status().isOk());

        verify(emailTicketRepository).findByAssignee(eq("uuid-me"), isNull(), any(Pageable.class));
    }

    @Test
    void listUnassignedFilterQueriesUnassigned() throws Exception {
        when(emailTicketRepository.findUnassigned(isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("assignee", "unassigned"))
                .andExpect(status().isOk());

        verify(emailTicketRepository).findUnassigned(isNull(), any(Pageable.class));
    }

    @Test
    void selfClaimAssignsToCaller() throws Exception {
        authenticateAs("uuid-me");
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/assign")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        ArgumentCaptor<EmailTicket> saved = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getAssigneeUserId()).isEqualTo("uuid-me");
        verify(auditService).record(eq(1L), eq(TicketAuditService.ACTION_ASSIGN), any());
    }

    @Test
    void adminCanAssignToAnotherOperator() throws Exception {
        authenticateAs("uuid-admin", "ADMIN");
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/assign")
                        .contentType("application/json").content("{\"userId\":\"uuid-other\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<EmailTicket> saved = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getAssigneeUserId()).isEqualTo("uuid-other");
    }

    @Test
    void operatorCannotAssignToAnotherOperator() throws Exception {
        authenticateAs("uuid-op");
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/assign")
                        .contentType("application/json").content("{\"userId\":\"uuid-other\"}"))
                .andExpect(status().isForbidden());

        verify(emailTicketRepository, never()).save(any());
    }

    @Test
    void unassignClearsAssignee() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setAssigneeUserId("uuid-me");
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/unassign")).andExpect(status().isOk());

        ArgumentCaptor<EmailTicket> saved = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getAssigneeUserId()).isNull();
        verify(auditService).record(eq(1L), eq(TicketAuditService.ACTION_UNASSIGN), any());
    }
}
