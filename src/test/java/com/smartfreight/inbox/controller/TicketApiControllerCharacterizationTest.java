package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.EmailReplyService;
import com.smartfreight.inbox.service.FeedbackLoopService;
import com.smartfreight.inbox.service.KeycloakAdminService;
import com.smartfreight.inbox.service.QuoteFollowUpService;
import com.smartfreight.inbox.service.SendRateLimiter;
import com.smartfreight.inbox.service.TicketAuditService;
import com.smartfreight.inbox.webhook.WebhookEventPublisher;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.mail.MailSendException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Characterisation tests for the ticket API: they record what the endpoints do today on the paths
 * {@link TicketApiControllerTest} leaves open (close, attach, send failure, list parameter guards,
 * drill-down views, assignee e-mails, assign edge cases), so the split by sub-resource can be checked
 * against them. Standalone MockMvc over the HTTP contract, like {@link TicketApiControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketApiControllerCharacterizationTest {

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
        TicketDetailAssembler details =
                new TicketDetailAssembler(emailTicketRepository, documentRepository, keycloakAdminService);
        TicketApiController controller = new TicketApiController(
                emailTicketRepository, auditService, keycloakAdminService, details);
        TicketReplyController replies = new TicketReplyController(
                emailTicketRepository, emailReplyService, auditService, sendRateLimiter,
                quoteFollowUpService, documentRepository, feedbackLoopService, webhookEventPublisher, details);
        mockMvc = MockMvcBuilders.standaloneSetup(controller, replies)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static Jwt.Builder jwt(String subject) {
        return Jwt.withTokenValue("token").header("alg", "none").subject(subject);
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

    private static TenantDocument document(long id, String identifier) {
        TenantDocument doc = new TenantDocument();
        doc.setId(id);
        doc.setIdentifier(identifier);
        doc.setDocType(DocumentType.POD);
        doc.setFilename("pod-" + id + ".pdf");
        doc.setContentType("application/pdf");
        doc.setFileSize(1024);
        return doc;
    }

    private Pageable capturedFindAllPageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(emailTicketRepository).findAll(pageable.capture());
        return pageable.getValue();
    }

    private void stubFindAll(List<EmailTicket> content) {
        when(emailTicketRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, Pageable.ofSize(50), content.size()));
    }

    // ---- list: parameter guards ----

    @Test
    void listUnknownSortColumnIsTheDefaultEscalatedView() throws Exception {
        stubFindAll(List.of());

        mockMvc.perform(get("/api/tickets").param("sort", "body").param("dir", "asc"))
                .andExpect(status().isOk());

        List<org.springframework.data.domain.Sort.Order> orders = capturedFindAllPageable().getSort().toList();
        assertThat(orders).extracting(org.springframework.data.domain.Sort.Order::getProperty)
                .containsExactly("sentimentNegative", "urgency", "createdAt");
        // dir applies to the fallback column, never to the escalation keys.
        assertThat(orders.get(0).isDescending()).isTrue();
        assertThat(orders.get(2).isAscending()).isTrue();
    }

    @Test
    void listDirIsCaseInsensitiveAndAnythingElseIsDescending() throws Exception {
        stubFindAll(List.of());

        mockMvc.perform(get("/api/tickets").param("sort", "subject").param("dir", "ASC"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tickets").param("sort", "subject").param("dir", "sideways"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(emailTicketRepository, times(2)).findAll(pageable.capture());
        assertThat(pageable.getAllValues().get(0).getSort().getOrderFor("subject").isAscending()).isTrue();
        assertThat(pageable.getAllValues().get(1).getSort().getOrderFor("subject").isDescending()).isTrue();
    }

    @Test
    void listClampsNegativePageAndZeroSize() throws Exception {
        stubFindAll(List.of());

        mockMvc.perform(get("/api/tickets").param("page", "-3").param("size", "0"))
                .andExpect(status().isOk());

        Pageable pageable = capturedFindAllPageable();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(1);
    }

    @Test
    void listCapsPageSizeAt200() throws Exception {
        stubFindAll(List.of());

        mockMvc.perform(get("/api/tickets").param("page", "4").param("size", "5000"))
                .andExpect(status().isOk());

        Pageable pageable = capturedFindAllPageable();
        assertThat(pageable.getPageNumber()).isEqualTo(4);
        assertThat(pageable.getPageSize()).isEqualTo(200);
    }

    @Test
    void listDefaultPageSizeIs50() throws Exception {
        stubFindAll(List.of());

        mockMvc.perform(get("/api/tickets")).andExpect(status().isOk());

        assertThat(capturedFindAllPageable().getPageSize()).isEqualTo(50);
    }

    @Test
    void listUnknownStatusIsIgnored() throws Exception {
        stubFindAll(List.of());

        mockMvc.perform(get("/api/tickets").param("status", "NOPE")).andExpect(status().isOk());

        verify(emailTicketRepository).findAll(any(Pageable.class));
        verify(emailTicketRepository, never()).findByStatus(any(), any(Pageable.class));
    }

    @Test
    void listStatusIsTrimmedAndCaseInsensitive() throws Exception {
        when(emailTicketRepository.findByStatus(eq(TicketStatus.CLOSED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("status", " closed ")).andExpect(status().isOk());

        verify(emailTicketRepository).findByStatus(eq(TicketStatus.CLOSED), any(Pageable.class));
    }

    @Test
    void listBlankStatusIsNoFilter() throws Exception {
        stubFindAll(List.of());

        mockMvc.perform(get("/api/tickets").param("status", "  ")).andExpect(status().isOk());

        verify(emailTicketRepository).findAll(any(Pageable.class));
    }

    @Test
    void listMineWithoutCallerIsAnEmptyPageWithoutQuerying() throws Exception {
        mockMvc.perform(get("/api/tickets").param("assignee", "mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(emailTicketRepository, never()).findByAssignee(any(), any(), any(Pageable.class));
        verify(emailTicketRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void listAssigneeModeIsTrimmedAndCaseInsensitiveAndCarriesTheStatus() throws Exception {
        authenticate(jwt("uuid-me").build());
        when(emailTicketRepository.findByAssignee(eq("uuid-me"), eq(TicketStatus.DRAFT_READY), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("assignee", " MINE ").param("status", "DRAFT_READY"))
                .andExpect(status().isOk());

        verify(emailTicketRepository).findByAssignee(eq("uuid-me"), eq(TicketStatus.DRAFT_READY), any(Pageable.class));
    }

    @Test
    void listUnassignedCarriesTheStatus() throws Exception {
        when(emailTicketRepository.findUnassigned(eq(TicketStatus.FAILED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("assignee", "unassigned").param("status", "FAILED"))
                .andExpect(status().isOk());

        verify(emailTicketRepository).findUnassigned(eq(TicketStatus.FAILED), any(Pageable.class));
    }

    @Test
    void listUnknownAssigneeModeIsEverything() throws Exception {
        stubFindAll(List.of());

        mockMvc.perform(get("/api/tickets").param("assignee", "somebody")).andExpect(status().isOk());

        verify(emailTicketRepository).findAll(any(Pageable.class));
    }

    // ---- list: summary-chip drill-down views ----

    @Test
    void viewAwaitingDrillsDownToDraftReady() throws Exception {
        when(emailTicketRepository.findByStatus(eq(TicketStatus.DRAFT_READY), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("view", " Awaiting ")).andExpect(status().isOk());

        verify(emailTicketRepository).findByStatus(eq(TicketStatus.DRAFT_READY), any(Pageable.class));
    }

    @Test
    void viewAutoSentTodayDrillsDownFromStartOfToday() throws Exception {
        when(emailTicketRepository.findByStatusAndUpdatedAtGreaterThanEqual(
                eq(TicketStatus.AUTO_SENT), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("view", "autosent_today")).andExpect(status().isOk());

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(emailTicketRepository).findByStatusAndUpdatedAtGreaterThanEqual(
                eq(TicketStatus.AUTO_SENT), since.capture(), any(Pageable.class));
        assertThat(since.getValue()).isEqualTo(LocalDate.now().atStartOfDay());
    }

    @Test
    void viewOpenCountsEveryNonTerminalStatus() throws Exception {
        when(emailTicketRepository.findByStatusIn(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("view", "open")).andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<TicketStatus>> statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(emailTicketRepository).findByStatusIn(statuses.capture(), any(Pageable.class));
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                TicketStatus.RECEIVED, TicketStatus.NEW, TicketStatus.PROCESSING,
                TicketStatus.DRAFT_READY, TicketStatus.RETRY_LATER, TicketStatus.FAILED);
    }

    @Test
    void viewTakesPrecedenceOverStatusAndAssigneeFilters() throws Exception {
        authenticate(jwt("uuid-me").build());
        when(emailTicketRepository.findByStatusInAndSlaBreachedTrue(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("view", "breached")
                        .param("status", "CLOSED").param("assignee", "mine"))
                .andExpect(status().isOk());

        verify(emailTicketRepository).findByStatusInAndSlaBreachedTrue(any(), any(Pageable.class));
        verify(emailTicketRepository, never()).findByAssignee(any(), any(), any(Pageable.class));
        verify(emailTicketRepository, never()).findByStatus(any(), any(Pageable.class));
    }

    @Test
    void blankViewFallsBackToTheFilters() throws Exception {
        stubFindAll(List.of());

        mockMvc.perform(get("/api/tickets").param("view", "  ")).andExpect(status().isOk());

        verify(emailTicketRepository).findAll(any(Pageable.class));
    }

    @Test
    void viewPageResolvesAssigneeEmails() throws Exception {
        authenticate(jwt("uuid-me").claim("tenant_id", 7L).build());
        EmailTicket assigned = ticket(1, TicketStatus.DRAFT_READY);
        assigned.setAssigneeUserId("uuid-a");
        when(emailTicketRepository.findByStatusIn(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(assigned), Pageable.ofSize(50), 1));
        when(keycloakAdminService.userEmailsByTenant(7L)).thenReturn(Map.of("uuid-a", "a@ops.example"));

        mockMvc.perform(get("/api/tickets").param("view", "open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].assigneeUserId").value("uuid-a"))
                .andExpect(jsonPath("$.content[0].assigneeEmail").value("a@ops.example"));
    }

    // ---- list: assignee e-mails (one Keycloak call per page, best-effort) ----

    @Test
    void listResolvesAssigneeEmailsInOneCallForTheCallersTenant() throws Exception {
        authenticate(jwt("uuid-me").claim("tenant_id", 7L).build());
        EmailTicket a = ticket(1, TicketStatus.DRAFT_READY);
        a.setAssigneeUserId("uuid-a");
        EmailTicket b = ticket(2, TicketStatus.DRAFT_READY);
        b.setAssigneeUserId("uuid-gone");
        EmailTicket free = ticket(3, TicketStatus.DRAFT_READY);
        stubFindAll(List.of(a, b, free));
        when(keycloakAdminService.userEmailsByTenant(7L)).thenReturn(Map.of("uuid-a", "a@ops.example"));

        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].assigneeEmail").value("a@ops.example"))
                .andExpect(jsonPath("$.content[1].assigneeUserId").value("uuid-gone"))
                .andExpect(jsonPath("$.content[1].assigneeEmail").doesNotExist())
                .andExpect(jsonPath("$.content[2].assigneeUserId").doesNotExist());

        verify(keycloakAdminService, times(1)).userEmailsByTenant(7L);
    }

    @Test
    void listWithoutAssignedTicketsDoesNotCallKeycloak() throws Exception {
        authenticate(jwt("uuid-me").claim("tenant_id", 7L).build());
        stubFindAll(List.of(ticket(1, TicketStatus.DRAFT_READY)));

        mockMvc.perform(get("/api/tickets")).andExpect(status().isOk());

        verifyNoInteractions(keycloakAdminService);
    }

    @Test
    void listWithoutTenantClaimSkipsEmailResolution() throws Exception {
        authenticate(jwt("uuid-me").build());
        EmailTicket a = ticket(1, TicketStatus.DRAFT_READY);
        a.setAssigneeUserId("uuid-a");
        stubFindAll(List.of(a));

        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].assigneeUserId").value("uuid-a"))
                .andExpect(jsonPath("$.content[0].assigneeEmail").doesNotExist());

        verifyNoInteractions(keycloakAdminService);
    }

    @Test
    void listStillAnswersWhenKeycloakIsDown() throws Exception {
        authenticate(jwt("uuid-me").claim("tenant_id", 7L).build());
        EmailTicket a = ticket(1, TicketStatus.DRAFT_READY);
        a.setAssigneeUserId("uuid-a");
        stubFindAll(List.of(a));
        when(keycloakAdminService.userEmailsByTenant(7L)).thenThrow(new IllegalStateException("down"));

        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].assigneeUserId").value("uuid-a"))
                .andExpect(jsonPath("$.content[0].assigneeEmail").doesNotExist());
    }

    // ---- detail: documents and assignee ----

    @Test
    void detailResolvesTheAttachedDocument() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setAttachedDocumentId(5L);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(documentRepository.findById(5L)).thenReturn(Optional.of(document(5, "CMAU1234567")));

        mockMvc.perform(get("/api/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachedDocument.id").value(5))
                .andExpect(jsonPath("$.attachedDocument.filename").value("pod-5.pdf"))
                .andExpect(jsonPath("$.availableDocuments").isEmpty());

        verify(documentRepository, never()).findByIdentifierOrderByUploadedAtDesc(any());
    }

    @Test
    void detailWithAVanishedAttachedDocumentShowsNone() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setAttachedDocumentId(5L);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(documentRepository.findById(5L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachedDocument").doesNotExist());
    }

    @Test
    void detailOfADocumentRequestListsTheDocumentsOnFile() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setTicketType(TicketType.DOCUMENT_REQUEST);
        t.setExtractedIdentifier("CMAU1234567");
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(documentRepository.findByIdentifierOrderByUploadedAtDesc("CMAU1234567"))
                .thenReturn(List.of(document(8, "CMAU1234567"), document(6, "CMAU1234567")));

        mockMvc.perform(get("/api/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableDocuments[0].id").value(8))
                .andExpect(jsonPath("$.availableDocuments[1].id").value(6));
    }

    @Test
    void detailOfADocumentRequestWithoutIdentifierListsNothing() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setTicketType(TicketType.DOCUMENT_REQUEST);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(get("/api/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableDocuments").isEmpty());

        verify(documentRepository, never()).findByIdentifierOrderByUploadedAtDesc(any());
    }

    @Test
    void detailOfAnotherTypeWithIdentifierListsNothing() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setTicketType(TicketType.STATUS_UPDATE);
        t.setExtractedIdentifier("CMAU1234567");
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(get("/api/tickets/1")).andExpect(status().isOk());

        verify(documentRepository, never()).findByIdentifierOrderByUploadedAtDesc(any());
    }

    @Test
    void detailResolvesTheAssigneeEmailForTheTicketsTenant() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setTenantId(9L);
        t.setAssigneeUserId("uuid-a");
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(keycloakAdminService.userEmailsByTenant(9L)).thenReturn(Map.of("uuid-a", "a@ops.example"));

        mockMvc.perform(get("/api/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeUserId").value("uuid-a"))
                .andExpect(jsonPath("$.assigneeEmail").value("a@ops.example"));
    }

    @Test
    void detailWithoutTenantOnTheTicketSkipsEmailResolution() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setAssigneeUserId("uuid-a");
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(get("/api/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeEmail").doesNotExist());

        verifyNoInteractions(keycloakAdminService);
    }

    @Test
    void detailStillAnswersWhenKeycloakIsDown() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setTenantId(9L);
        t.setAssigneeUserId("uuid-a");
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(keycloakAdminService.userEmailsByTenant(9L)).thenThrow(new IllegalStateException("down"));

        mockMvc.perform(get("/api/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeUserId").value("uuid-a"))
                .andExpect(jsonPath("$.assigneeEmail").doesNotExist());
    }

    // ---- draft: the actions other than a successful send ----

    @Test
    void draftWithoutActionIsASave() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.FAILED);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json").content("{\"draftReply\":\"Hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT_READY"))
                .andExpect(jsonPath("$.draftReply").value("Hi"));

        verify(auditService).record(1L, TicketAuditService.ACTION_DRAFT_EDIT, null);
        verify(emailTicketRepository).save(t);
        verifyNoInteractions(emailReplyService, sendRateLimiter);
    }

    @Test
    void draftSaveOnADraftReadyTicketKeepsTheStatusAndAuditsTheEdit() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        LocalDateTime before = t.getUpdatedAt().minusMinutes(5);
        t.setUpdatedAt(before);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json").content("{\"action\":\"save\",\"draftReply\":\"Edited\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT_READY"));

        verify(auditService).record(1L, TicketAuditService.ACTION_DRAFT_EDIT, null);
        verify(emailTicketRepository).save(t);
        assertThat(t.getDraftReply()).isEqualTo("Edited");
        assertThat(t.getUpdatedAt()).isAfter(before);
    }

    @Test
    void draftUnknownActionIsASave() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.NEW);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json").content("{\"action\":\"publish\",\"draftReply\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT_READY"));

        verifyNoInteractions(emailReplyService);
    }

    @Test
    void draftCloseActionClosesWithTheTextAndWithoutSending() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json").content("{\"action\":\"CLOSE\",\"draftReply\":\"Kept\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.draftReply").value("Kept"));

        verify(auditService).record(1L, TicketAuditService.ACTION_CLOSE, null);
        verify(emailTicketRepository).save(t);
        verifyNoInteractions(emailReplyService, sendRateLimiter, quoteFollowUpService,
                feedbackLoopService, webhookEventPublisher);
    }

    @Test
    void draftSendActionIsCaseInsensitive() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(sendRateLimiter.tryAcquire(1L)).thenReturn(true);

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json").content("{\"action\":\"Send\",\"draftReply\":\"R\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        verify(auditService).record(1L, TicketAuditService.ACTION_SEND, "to=customer@x.com");
        verify(emailTicketRepository).save(t);
    }

    @Test
    void draftSendFailureIs502AuditedAndLeavesTheTicketUnsaved() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(sendRateLimiter.tryAcquire(anyLong())).thenReturn(true);
        org.mockito.Mockito.doThrow(new MailSendException("smtp down"))
                .when(emailReplyService).sendReply(any(), any());

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json").content("{\"action\":\"send\",\"draftReply\":\"R\"}"))
                .andExpect(status().isBadGateway());

        verify(auditService).record(1L, TicketAuditService.ACTION_SEND, "FAILED: smtp down");
        verify(emailTicketRepository, never()).save(any());
        verifyNoInteractions(quoteFollowUpService, feedbackLoopService, webhookEventPublisher);
        // The edited text and the status stay on the (managed) entity as they were set.
        assertThat(t.getDraftReply()).isEqualTo("R");
        assertThat(t.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
    }

    @Test
    void draftRateLimitedSendDoesNotSave() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(sendRateLimiter.tryAcquire(1L)).thenReturn(false);
        when(sendRateLimiter.getCooldown()).thenReturn(java.time.Duration.ofSeconds(5));

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json").content("{\"action\":\"send\",\"draftReply\":\"R\"}"))
                .andExpect(status().isTooManyRequests());

        verify(emailTicketRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void draftOnAClosedTicketIsANoOpReturningTheDetail() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.CLOSED);
        t.setDraftReply("Sent text");
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/draft")
                        .contentType("application/json").content("{\"action\":\"save\",\"draftReply\":\"New\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.draftReply").value("Sent text"));

        verify(emailTicketRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void draftOnAMissingTicketIs404() throws Exception {
        mockMvc.perform(post("/api/tickets/9/draft")
                        .contentType("application/json").content("{\"action\":\"save\",\"draftReply\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- close ----

    @Test
    void closeClosesSavesAndAudits() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        LocalDateTime before = t.getUpdatedAt().minusMinutes(5);
        t.setUpdatedAt(before);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        verify(emailTicketRepository).save(t);
        verify(auditService).record(1L, TicketAuditService.ACTION_CLOSE, null);
        assertThat(t.getUpdatedAt()).isAfter(before);
    }

    @Test
    void closeOfAnAlreadyClosedTicketClosesAndAuditsAgain() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.CLOSED);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        verify(emailTicketRepository).save(t);
        verify(auditService).record(1L, TicketAuditService.ACTION_CLOSE, null);
    }

    @Test
    void closeOfAnAutoSentTicketIsANoOp() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.AUTO_SENT);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTO_SENT"));

        verify(emailTicketRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void closeOfAMissingTicketIs404() throws Exception {
        mockMvc.perform(post("/api/tickets/9/close")).andExpect(status().isNotFound());
    }

    // ---- attach-document ----

    @Test
    void attachSetsTheDocumentAndReturnsItInTheDetail() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(documentRepository.findById(5L)).thenReturn(Optional.of(document(5, "CMAU1234567")));

        mockMvc.perform(post("/api/tickets/1/attach-document")
                        .contentType("application/json").content("{\"documentId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachedDocument.id").value(5));

        assertThat(t.getAttachedDocumentId()).isEqualTo(5L);
        verify(emailTicketRepository).save(t);
        // Attaching is not an audited action.
        verifyNoInteractions(auditService);
    }

    @Test
    void attachWithNullIdDetaches() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setAttachedDocumentId(5L);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/attach-document")
                        .contentType("application/json").content("{\"documentId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachedDocument").doesNotExist());

        assertThat(t.getAttachedDocumentId()).isNull();
        verify(emailTicketRepository).save(t);
    }

    @Test
    void attachOfAnUnknownDocumentIs404AndChangesNothing() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        t.setAttachedDocumentId(3L);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));
        when(documentRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/tickets/1/attach-document")
                        .contentType("application/json").content("{\"documentId\":99}"))
                .andExpect(status().isNotFound());

        assertThat(t.getAttachedDocumentId()).isEqualTo(3L);
        verify(emailTicketRepository, never()).save(any());
    }

    @Test
    void attachOnATerminalTicketIsANoOp() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.AUTO_SENT);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/attach-document")
                        .contentType("application/json").content("{\"documentId\":5}"))
                .andExpect(status().isOk());

        assertThat(t.getAttachedDocumentId()).isNull();
        verify(emailTicketRepository, never()).save(any());
        verify(documentRepository, never()).findById(5L);
    }

    @Test
    void attachOnAMissingTicketIs404() throws Exception {
        mockMvc.perform(post("/api/tickets/9/attach-document")
                        .contentType("application/json").content("{\"documentId\":5}"))
                .andExpect(status().isNotFound());
    }

    // ---- assign / unassign ----

    @Test
    void assignWithoutCallerIs401() throws Exception {
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket(1, TicketStatus.DRAFT_READY)));

        mockMvc.perform(post("/api/tickets/1/assign")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());

        verify(emailTicketRepository, never()).save(any());
    }

    @Test
    void assignOfAMissingTicketIs404EvenWithoutCaller() throws Exception {
        mockMvc.perform(post("/api/tickets/9/assign")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void assignWithoutBodySelfClaims() throws Exception {
        authenticate(jwt("uuid-me").build());
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/assign"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeUserId").value("uuid-me"));

        verify(auditService).record(1L, TicketAuditService.ACTION_ASSIGN, "to=uuid-me");
    }

    @Test
    void assignWithBlankUserIdSelfClaims() throws Exception {
        authenticate(jwt("uuid-me").build());
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/assign")
                        .contentType("application/json").content("{\"userId\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeUserId").value("uuid-me"));
    }

    @Test
    void operatorNamingThemselvesTrimmedIsASelfClaim() throws Exception {
        authenticate(jwt("uuid-me").build());
        EmailTicket t = ticket(1, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/assign")
                        .contentType("application/json").content("{\"userId\":\" uuid-me \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeUserId").value("uuid-me"));
    }

    @Test
    void superAdminCanAssignToAnotherOperator() throws Exception {
        authenticate(jwt("uuid-boss").claim("roles", List.of("OPERATOR", "SUPER_ADMIN")).build());
        EmailTicket t = ticket(1, TicketStatus.CLOSED);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/assign")
                        .contentType("application/json").content("{\"userId\":\" uuid-other \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeUserId").value("uuid-other"))
                // Ownership is independent of the lifecycle: a closed ticket can be assigned.
                .andExpect(jsonPath("$.status").value("CLOSED"));

        verify(auditService).record(1L, TicketAuditService.ACTION_ASSIGN, "to=uuid-other");
        verify(emailTicketRepository).save(t);
    }

    @Test
    void rolesClaimThatIsNotAListGrantsNoAdmin() throws Exception {
        authenticate(jwt("uuid-op").claim("roles", "ADMIN").build());
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket(1, TicketStatus.DRAFT_READY)));

        mockMvc.perform(post("/api/tickets/1/assign")
                        .contentType("application/json").content("{\"userId\":\"uuid-other\"}"))
                .andExpect(status().isForbidden());

        verify(emailTicketRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void missingRolesClaimGrantsNoAdmin() throws Exception {
        authenticate(jwt("uuid-op").build());
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket(1, TicketStatus.DRAFT_READY)));

        mockMvc.perform(post("/api/tickets/1/assign")
                        .contentType("application/json").content("{\"userId\":\"uuid-other\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unassignIsAuditedAndWorksOnAnyStatus() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.AUTO_SENT);
        t.setAssigneeUserId("uuid-a");
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(post("/api/tickets/1/unassign"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeUserId").doesNotExist());

        verify(emailTicketRepository).save(t);
        verify(auditService).record(1L, TicketAuditService.ACTION_UNASSIGN, null);
    }

    @Test
    void unassignOfAMissingTicketIs404() throws Exception {
        mockMvc.perform(post("/api/tickets/9/unassign")).andExpect(status().isNotFound());
    }

    // ---- delete ----

    @Test
    void deleteOfAMissingTicketIs404() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/tickets/9"))
                .andExpect(status().isNotFound());

        verify(emailTicketRepository, never()).delete(any());
    }

    @Test
    void deleteOfAClosedTicketIsAllowed() throws Exception {
        EmailTicket t = ticket(1, TicketStatus.CLOSED);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(t));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/tickets/1"))
                .andExpect(status().isNoContent());

        verify(emailTicketRepository).delete(t);
        verify(auditService).record(1L, TicketAuditService.ACTION_DELETE, null);
    }

    // ---- summary ----

    @Test
    void summaryCountsAutoSentFromStartOfToday() throws Exception {
        mockMvc.perform(get("/api/tickets/summary")).andExpect(status().isOk());

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(emailTicketRepository).countByStatusAndUpdatedAtGreaterThanEqual(
                eq(TicketStatus.AUTO_SENT), since.capture());
        assertThat(since.getValue()).isEqualTo(LocalDate.now().atStartOfDay());
        verify(emailTicketRepository).countByStatus(TicketStatus.DRAFT_READY);
        verify(emailTicketRepository, never()).findById(any());
    }

    @Test
    void listMineWithUnknownStatusQueriesWithoutStatus() throws Exception {
        authenticate(jwt("uuid-me").build());
        when(emailTicketRepository.findByAssignee(eq("uuid-me"), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        mockMvc.perform(get("/api/tickets").param("assignee", "mine").param("status", "garbage"))
                .andExpect(status().isOk());

        verify(emailTicketRepository).findByAssignee(eq("uuid-me"), isNull(), any(Pageable.class));
    }
}
