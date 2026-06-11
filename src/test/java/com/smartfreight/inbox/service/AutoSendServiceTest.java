package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the auto-send threshold (Phase 1.6).
 *
 * Covers the three pillars separately:
 * 1. confidenceFor — the deterministic heuristic (pure function, no mocks needed);
 * 2. shouldAutoSend — the three gates: feature flag (default OFF), type allowlist, threshold;
 * 3. autoSend — send + AUTO_SENT transition, and every refusal path: wrong status (redelivery
 *    idempotency), rate limiter, SMTP failure leaving the ticket DRAFT_READY for the operator.
 */
@ExtendWith(MockitoExtension.class)
class AutoSendServiceTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private EmailReplyService emailReplyService;

    @Mock
    private SettingsService settingsService;

    @Mock
    private SendRateLimiter sendRateLimiter;

    @Mock
    private TicketAuditService auditService;

    private AutoSendService service;

    @BeforeEach
    void setUp() {
        service = new AutoSendService(emailTicketRepository, emailReplyService,
                settingsService, sendRateLimiter, auditService, new SimpleMeterRegistry());
    }

    private EmailTicket ticket(TicketStatus status, TicketType type, Double confidence) {
        return EmailTicket.builder()
                .id(1L)
                .messageId("<msg-001@example.com>")
                .senderEmail("customer@example.com")
                .subject("Where is my container?")
                .body("Status of MSCU1234567 please")
                .status(status)
                .ticketType(type)
                .aiConfidence(confidence)
                .draftReply("Dear customer, your container is at Felixstowe.")
                .build();
    }

    /**
     * Settings stubs for an enabled feature with defaults (threshold 0.8, STATUS_UPDATE only).
     * Lenient because the gates short-circuit — e.g. a type-gate test never reads the threshold.
     */
    private void stubEnabledDefaults() {
        lenient().when(settingsService.isFeatureEnabled(AutoSendService.FEATURE_KEY, false)).thenReturn(true);
        lenient().when(settingsService.getString(eq(AutoSendService.KEY_THRESHOLD), anyString()))
                .thenReturn("0.8");
        lenient().when(settingsService.getString(eq(AutoSendService.KEY_TYPES), anyString()))
                .thenReturn("STATUS_UPDATE");
    }

    // ---- confidenceFor: pure heuristic -------------------------------------------------

    @Test
    void confidence_allSignals_isOne() {
        assertThat(service.confidenceFor(TicketType.RFQ, true, true, true)).isEqualTo(1.0);
    }

    @Test
    void confidence_typedAndItemFound_clearsDefaultThreshold() {
        // STATUS_UPDATE with the item found in the backend: 0.3 + 0.2 + 0.4 = 0.9 ≥ 0.8
        assertThat(service.confidenceFor(TicketType.STATUS_UPDATE, true, true, false)).isEqualTo(0.9);
    }

    @Test
    void confidence_itemNotFound_staysBelowDefaultThreshold() {
        // The draft is not grounded in backend data — must not clear 0.8.
        assertThat(service.confidenceFor(TicketType.STATUS_UPDATE, true, false, false)).isEqualTo(0.5);
    }

    @Test
    void confidence_generalOrNullType_neverReachesThreshold() {
        assertThat(service.confidenceFor(TicketType.GENERAL, true, true, true)).isEqualTo(0.7);
        assertThat(service.confidenceFor(null, true, true, true)).isEqualTo(0.7);
    }

    @Test
    void confidence_nothing_isZero() {
        assertThat(service.confidenceFor(null, false, false, false)).isEqualTo(0.0);
    }

    // ---- shouldAutoSend: the three gates ------------------------------------------------

    @Test
    void featureFlag_defaultsToOff() {
        when(settingsService.isFeatureEnabled(AutoSendService.FEATURE_KEY, false)).thenReturn(false);

        boolean result = service.shouldAutoSend(ticket(TicketStatus.DRAFT_READY, TicketType.STATUS_UPDATE, 1.0));

        assertThat(result).isFalse();
        // The flag gate comes first — no settings reads, no other checks.
        verify(settingsService, never()).getString(any(), any());
    }

    @Test
    void typeNotInAllowlist_blocked() {
        stubEnabledDefaults();

        assertThat(service.shouldAutoSend(ticket(TicketStatus.DRAFT_READY, TicketType.RFQ, 1.0))).isFalse();
        assertThat(service.shouldAutoSend(ticket(TicketStatus.DRAFT_READY, null, 1.0))).isFalse();
    }

    @Test
    void confidenceBelowThreshold_blocked() {
        stubEnabledDefaults();

        assertThat(service.shouldAutoSend(ticket(TicketStatus.DRAFT_READY, TicketType.STATUS_UPDATE, 0.79))).isFalse();
        assertThat(service.shouldAutoSend(ticket(TicketStatus.DRAFT_READY, TicketType.STATUS_UPDATE, null))).isFalse();
    }

    @Test
    void allGatesPass_allowed() {
        stubEnabledDefaults();

        assertThat(service.shouldAutoSend(ticket(TicketStatus.DRAFT_READY, TicketType.STATUS_UPDATE, 0.9))).isTrue();
    }

    @Test
    void malformedThreshold_fallsBackToDefault() {
        when(settingsService.getString(eq(AutoSendService.KEY_THRESHOLD), anyString()))
                .thenReturn("not-a-number");

        assertThat(service.threshold()).isEqualTo(AutoSendService.DEFAULT_THRESHOLD);
    }

    @Test
    void thresholdOutOfRange_isClamped() {
        when(settingsService.getString(eq(AutoSendService.KEY_THRESHOLD), anyString()))
                .thenReturn("7");

        assertThat(service.threshold()).isEqualTo(1.0);
    }

    @Test
    void unknownTypeNamesInSetting_areIgnored() {
        when(settingsService.getString(eq(AutoSendService.KEY_TYPES), anyString()))
                .thenReturn("STATUS_UPDATE, NOT_A_TYPE,, rfq");

        assertThat(service.allowedTypes())
                .containsExactlyInAnyOrder(TicketType.STATUS_UPDATE, TicketType.RFQ);
    }

    // ---- autoSend: transition + refusal paths -------------------------------------------

    @Test
    void autoSend_happyPath_sendsAndMarksAutoSent() {
        EmailTicket ticket = ticket(TicketStatus.DRAFT_READY, TicketType.STATUS_UPDATE, 0.9);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubEnabledDefaults();
        when(sendRateLimiter.tryAcquire(1L)).thenReturn(true);

        service.autoSend(1L);

        verify(emailReplyService).sendReply(ticket);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.AUTO_SENT);
        verify(emailTicketRepository).save(ticket);
        verify(auditService).record(eq(1L), eq(TicketAuditService.ACTION_AUTO_SEND),
                contains("confidence=0.9"));
    }

    @Test
    void autoSend_notDraftReady_isIdempotentNoOp() {
        // At-least-once delivery: a redelivered event must not re-send an AUTO_SENT ticket.
        EmailTicket ticket = ticket(TicketStatus.AUTO_SENT, TicketType.STATUS_UPDATE, 0.9);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        service.autoSend(1L);

        verifyNoInteractions(emailReplyService, sendRateLimiter);
        verify(emailTicketRepository, never()).save(any());
    }

    @Test
    void autoSend_rateLimited_skipsWithoutSending() {
        EmailTicket ticket = ticket(TicketStatus.DRAFT_READY, TicketType.STATUS_UPDATE, 0.9);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubEnabledDefaults();
        when(sendRateLimiter.tryAcquire(1L)).thenReturn(false);

        service.autoSend(1L);

        verifyNoInteractions(emailReplyService);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
    }

    @Test
    void autoSend_smtpFailure_leavesTicketDraftReadyForOperator() {
        EmailTicket ticket = ticket(TicketStatus.DRAFT_READY, TicketType.STATUS_UPDATE, 0.9);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubEnabledDefaults();
        when(sendRateLimiter.tryAcquire(1L)).thenReturn(true);
        doThrow(new RuntimeException("SMTP down")).when(emailReplyService).sendReply(ticket);

        service.autoSend(1L);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        verify(emailTicketRepository, never()).save(any());
        verify(auditService).record(eq(1L), eq(TicketAuditService.ACTION_AUTO_SEND),
                contains("FAILED"));
    }

    @Test
    void autoSend_settingsChangedSinceDecision_reChecksGates() {
        // The pipeline judged the ticket eligible, but the operator switched the flag off
        // before the send fired — the re-check must refuse.
        EmailTicket ticket = ticket(TicketStatus.DRAFT_READY, TicketType.STATUS_UPDATE, 0.9);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(settingsService.isFeatureEnabled(AutoSendService.FEATURE_KEY, false)).thenReturn(false);

        service.autoSend(1L);

        verifyNoInteractions(emailReplyService);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
    }
}
