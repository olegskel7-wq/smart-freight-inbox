package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TrackedItem;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * AI pipeline orchestrator for ticket processing.
 *
 * Coordinates the pipeline steps:
 * 0. Relevance + type classification (Qwen 2.5) for RECEIVED tickets — non-freight mail is
 *    parked as IRRELEVANT and never reaches the heavier steps;
 *    relevant mail gets its business type (RFQ/STATUS_UPDATE/…) in the same call
 *    ({@link TicketClassificationStep})
 * 1. Item identifier extraction (Qwen 2.5) from the email body
 * 2. Item lookup via the backend ({@link ItemLookupService}) — both in {@link IdentifierLookupStep}
 * 3. Reply draft generation (Llama 3.1) — {@link ReplyDraftStep}, context from {@link DraftContextAssembler}
 * then confidence, shipment watch, auto-send decision and Gmail draft ({@link DraftRoutingStep}).
 *
 * The steps only compute and set ticket fields; this class owns every status transition, every
 * save and the transaction / retry semantics around them.
 *
 * Executed synchronously in the calling thread — the RabbitMQ
 * consumer ({@link com.smartfreight.inbox.messaging.TicketEventConsumer}), never the
 * IMAP thread.
 *
 * Protection against optimistic lock conflicts: automatically retries up to MAX_RETRY_ATTEMPTS
 * (3 times). Each attempt runs in its own REQUIRES_NEW transaction so that a failed attempt's
 * rollback-only state does not poison the next attempt.
 */
@Service
public class TicketProcessingService {

    private static final Logger log = LoggerFactory.getLogger(TicketProcessingService.class);

    /** Maximum number of retry attempts on an optimistic lock conflict. */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * Per-tenant setting: the Keycloak user UUID that escalated (HIGH urgency / negative
     * sentiment) tickets are auto-assigned to. Blank/unset → no auto-assign (escalation still tightens
     * the SLA + floats the ticket to the top of the queue). Resolved via the standard
     * {@code tenant_setting → app_setting → default} chain.
     */
    public static final String SETTING_ESCALATION_ASSIGNEE = "routing.escalation-assignee";

    private final EmailTicketRepository emailTicketRepository;
    private final TicketClassificationStep ticketClassificationStep;
    private final IdentifierLookupStep identifierLookupStep;
    private final ReplyDraftStep replyDraftStep;
    private final DraftRoutingStep draftRoutingStep;
    private final AutoSendService autoSendService;
    private final Timer processingTimer;
    private final Counter draftReadyCounter;
    private final Counter failedCounter;
    private final Counter parkedCounter;

    /**
     * Self-reference to the Spring proxy so that @Transactional on processAttempt()
     * and markFailed() is honoured on intra-class calls.
     * @Lazy breaks the circular-dependency cycle during context startup.
     */
    @Autowired
    @Lazy
    private TicketProcessingService self;

    public TicketProcessingService(EmailTicketRepository emailTicketRepository,
                                   TicketClassificationStep ticketClassificationStep,
                                   IdentifierLookupStep identifierLookupStep,
                                   ReplyDraftStep replyDraftStep,
                                   DraftRoutingStep draftRoutingStep,
                                   AutoSendService autoSendService,
                                   MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketClassificationStep = ticketClassificationStep;
        this.identifierLookupStep = identifierLookupStep;
        this.replyDraftStep = replyDraftStep;
        this.draftRoutingStep = draftRoutingStep;
        this.autoSendService = autoSendService;
        this.processingTimer = Timer.builder("smartfreight_processing_duration_seconds")
                .description("Duration of ticket processing through AI pipeline")
                .register(meterRegistry);
        this.draftReadyCounter = Counter.builder("smartfreight_tickets_status_total")
                .tag("status", "DRAFT_READY")
                .description("Ticket count by status")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("smartfreight_tickets_status_total")
                .tag("status", "FAILED")
                .description("Ticket count by status")
                .register(meterRegistry);
        this.parkedCounter = Counter.builder("smartfreight_tickets_parked_total")
                .description("Tickets parked as RETRY_LATER because a circuit breaker was open")
                .register(meterRegistry);
    }

    /**
     * Processes a ticket through the three-step AI pipeline.
     *
     * Not transactional itself — each attempt delegates to {@link #processAttempt} which
     * opens its own REQUIRES_NEW transaction. This ensures that when Hibernate throws
     * {@link ObjectOptimisticLockingFailureException} and marks a transaction rollback-only,
     * the next attempt starts with a completely fresh transaction rather than inheriting
     * the poisoned one.
     *
     * "Pass ID, not entity" pattern: the ticket is reloaded inside processAttempt because
     * a passed detached entity would cause LazyInitializationException.
     *
     * @param ticketId identifier of the ticket to process
     */
    public void process(Long ticketId) {
        MDC.put("ticketId", String.valueOf(ticketId));
        long startTime = System.nanoTime();
        try {
            log.info("Starting AI processing");

            for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
                try {
                    boolean autoSendEligible = self.processAttempt(ticketId, startTime);
                    // The SMTP send happens strictly AFTER the pipeline transaction
                    // committed — an optimistic-lock retry of the attempt can therefore never
                    // repeat a send, and a crash here leaves a safe DRAFT_READY ticket.
                    if (autoSendEligible) {
                        autoSendService.autoSend(ticketId);
                    }
                    return;
                } catch (ObjectOptimisticLockingFailureException e) {
                    log.warn("Optimistic lock conflict on attempt {}/{}, retrying",
                            attempt + 1, MAX_RETRY_ATTEMPTS);
                    if (attempt == MAX_RETRY_ATTEMPTS - 1) {
                        log.error("All retry attempts exhausted — marking ticket as FAILED");
                        self.markFailed(ticketId);
                    }
                } catch (CallNotPermittedException e) {
                    // A circuit breaker is OPEN — the dependency (Ollama) is known to
                    // be down. Park the ticket instead of failing it or serving a degraded
                    // fallback draft: RetryScheduler re-runs the pipeline once the breaker
                    // closes, and the customer gets a proper AI draft, just later. The attempt's
                    // transaction has rolled back, so the ticket is in its pre-attempt state.
                    log.warn("Circuit breaker '{}' is open — parking ticket as RETRY_LATER",
                            e.getCausingCircuitBreakerName());
                    self.markRetryLater(ticketId);
                    return;
                } catch (Exception e) {
                    log.error("Unexpected error during ticket processing", e);
                    self.markFailed(ticketId);
                    return;
                }
            }
        } finally {
            MDC.remove("ticketId");
        }
    }

    /**
     * Executes one attempt of the AI pipeline inside a dedicated transaction.
     *
     * REQUIRES_NEW guarantees a fresh transaction for every call, so an optimistic lock
     * failure on attempt N cannot affect attempt N+1.
     *
     * @param ticketId  identifier of the ticket to process
     * @param startTime {@code System.nanoTime()} captured at the start of {@link #process}
     *                  for end-to-end duration measurement
     * @return whether the ticket reached DRAFT_READY and qualifies for auto-send —
     *         the caller performs the actual send after this transaction commits
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processAttempt(Long ticketId, long startTime) {
        EmailTicket ticket = emailTicketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));

        if (ticket.getStatus() != TicketStatus.RECEIVED
                && ticket.getStatus() != TicketStatus.NEW
                && ticket.getStatus() != TicketStatus.RETRY_LATER) {
            log.warn("Ticket already in state {}, skipping", ticket.getStatus());
            return false;
        }

        if (ticketClassificationStep.needsClassification(ticket)) {
            log.info("Step 0: Classifying email relevance and type via Qwen 2.5");
            if (!ticketClassificationStep.classify(ticket, buildAiInput(ticket))) {
                ticket.setStatus(TicketStatus.IRRELEVANT);
                // Spam carries no SLA — drop the provisional deadline set at ingest.
                ticket.setSlaDeadlineAt(null);
                ticket.setUpdatedAt(LocalDateTime.now());
                emailTicketRepository.save(ticket);
                log.info("Ticket classified as IRRELEVANT — pipeline stopped, kept for audit");
                return false;
            }
        }

        ticket.setStatus(TicketStatus.PROCESSING);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.saveAndFlush(ticket);

        // The AI sees the email body plus any parsed attachment text as one input.
        String aiInput = buildAiInput(ticket);

        log.info("Step 1/3: Extracting item identifier via Qwen 2.5");
        String identifier = identifierLookupStep.extract(aiInput);
        ticket.setExtractedIdentifier(identifier.isEmpty() ? null : identifier);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);

        log.info("Step 2/3: Looking up item '{}' via backend", identifier);
        TrackedItem item = identifierLookupStep.lookup(identifier);
        if (item == null) {
            log.warn("Item '{}' not found — draft will reflect missing data", identifier);
        }

        log.info("Step 3/3: Generating reply draft via Llama 3.1");
        ReplyDraftStep.ReplyDraft draft = replyDraftStep.draft(ticket, aiInput, identifier, item);
        ticket.setDraftReply(draft.text());
        // Preserve the unmodified AI draft so the feedback loop can compare it against
        // what the operator actually sends. draftReply gets overwritten on edit; this does not.
        ticket.setAiOriginalDraft(draft.text());

        ticket.setAiConfidence(draftRoutingStep.confidence(ticket, item, draft.rateMatched()));

        ticket.setStatus(TicketStatus.DRAFT_READY);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);

        draftReadyCounter.increment();
        processingTimer.record(System.nanoTime() - startTime, java.util.concurrent.TimeUnit.NANOSECONDS);
        log.info("Ticket processing finished: DRAFT_READY (confidence {})", ticket.getAiConfidence());

        return draftRoutingStep.route(ticket, item);
    }

    /**
     * Builds the text the AI models work on: the email body plus any parsed attachment text.
     * When the email has no attachments this is just the body, preserving the
     * previous behaviour.
     *
     * @param ticket the ticket being processed
     * @return combined body + attachment text
     */
    private String buildAiInput(EmailTicket ticket) {
        String body = ticket.getBody() != null ? ticket.getBody() : "";
        String attachments = ticket.getAttachmentText();
        if (attachments == null || attachments.isBlank()) {
            return body;
        }
        return body + "\n\n" + attachments;
    }

    /**
     * Marks a ticket as FAILED in its own transaction.
     *
     * Public and REQUIRES_NEW so it can be called through the Spring proxy from
     * {@link #process} after a failed attempt's transaction has already been rolled back.
     *
     * @param ticketId ticket identifier
     */
    /**
     * Parks a ticket as RETRY_LATER because a circuit breaker rejected a pipeline call.
     * Runs in its own transaction for the same reason as {@link #markFailed}:
     * the failed attempt's transaction has already rolled back.
     *
     * retryCount is deliberately NOT incremented here — the breaker being open is not the
     * ticket's fault, and {@link com.smartfreight.inbox.scheduler.RetryScheduler} skips
     * re-enqueueing entirely while the breaker stays open, so the retry budget is spent
     * only on real processing attempts.
     *
     * @param ticketId ticket identifier
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRetryLater(Long ticketId) {
        emailTicketRepository.findById(ticketId).ifPresent(t -> {
            t.setStatus(TicketStatus.RETRY_LATER);
            t.setUpdatedAt(LocalDateTime.now());
            emailTicketRepository.save(t);
            parkedCounter.increment();
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long ticketId) {
        emailTicketRepository.findById(ticketId).ifPresent(t -> {
            t.setStatus(TicketStatus.FAILED);
            t.setUpdatedAt(LocalDateTime.now());
            emailTicketRepository.save(t);
            failedCounter.increment();
        });
    }

    /**
     * Prepares tickets for bulk reprocessing — bulk FAILED → NEW reset.
     *
     * Used by {@link com.smartfreight.inbox.controller.AdminController#reprocessAll}.
     * Isolated into a separate @Transactional method for two reasons:
     * 1. The transaction is short (one UPDATE, immediate commit).
     *    The HikariCP connection is returned to the pool right away.
     * 2. A bulk update requires @Transactional + @Modifying at the JPA level,
     *    otherwise Hibernate throws TransactionRequiredException.
     *
     * @return number of tickets reset from FAILED to NEW
     */
    @Transactional
    public int prepareForRetry() {
        int reset = emailTicketRepository.resetFailedToNew(TicketStatus.NEW, TicketStatus.FAILED);
        if (reset > 0) {
            log.info("prepareForRetry: reset {} FAILED ticket(s) to NEW", reset);
        }
        return reset;
    }
}
