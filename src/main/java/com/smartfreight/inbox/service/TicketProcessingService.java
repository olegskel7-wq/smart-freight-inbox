package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TrackedItem;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
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
import java.util.Optional;

/**
 * AI pipeline orchestrator for ticket processing.
 *
 * Coordinates the pipeline steps:
 * 0. Relevance + type classification (Qwen 2.5) for RECEIVED tickets — non-freight mail is
 *    parked as IRRELEVANT and never reaches the heavier steps (Phase 1.3 session 3);
 *    relevant mail gets its business type (RFQ/STATUS_UPDATE/…) in the same call (Phase 1.4)
 * 1. Item identifier extraction (Qwen 2.5) from the email body
 * 2. Item lookup via the backend ({@link ItemLookupService})
 * 3. Reply draft generation (Llama 3.1)
 *
 * Executed synchronously in the calling thread — since Phase 1.3 that is the RabbitMQ
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

    private final EmailTicketRepository emailTicketRepository;
    private final EmailClassificationService emailClassificationService;
    private final AiExtractionService aiExtractionService;
    private final ItemLookupService itemLookupService;
    private final AiDraftingService aiDraftingService;
    private final RateSheetService rateSheetService;
    private final GmailDraftService gmailDraftService;
    private final SlaService slaService;
    private final AutoSendService autoSendService;
    private final ShipmentWatchService shipmentWatchService;
    private final DocumentRequestService documentRequestService;
    private final FeedbackLoopService feedbackLoopService;
    private final SettingsService settingsService;
    private final Timer processingTimer;
    private final Counter draftReadyCounter;
    private final Counter failedCounter;
    private final Counter parkedCounter;
    private final Counter documentAttachedCounter;
    private final Counter documentEscalatedCounter;

    /**
     * Self-reference to the Spring proxy so that @Transactional on processAttempt()
     * and markFailed() is honoured on intra-class calls.
     * @Lazy breaks the circular-dependency cycle during context startup.
     */
    @Autowired
    @Lazy
    private TicketProcessingService self;

    public TicketProcessingService(EmailTicketRepository emailTicketRepository,
                                   EmailClassificationService emailClassificationService,
                                   AiExtractionService aiExtractionService,
                                   ItemLookupService itemLookupService,
                                   AiDraftingService aiDraftingService,
                                   RateSheetService rateSheetService,
                                   GmailDraftService gmailDraftService,
                                   SlaService slaService,
                                   AutoSendService autoSendService,
                                   ShipmentWatchService shipmentWatchService,
                                   DocumentRequestService documentRequestService,
                                   FeedbackLoopService feedbackLoopService,
                                   SettingsService settingsService,
                                   MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.emailClassificationService = emailClassificationService;
        this.aiExtractionService = aiExtractionService;
        this.itemLookupService = itemLookupService;
        this.aiDraftingService = aiDraftingService;
        this.rateSheetService = rateSheetService;
        this.gmailDraftService = gmailDraftService;
        this.slaService = slaService;
        this.autoSendService = autoSendService;
        this.shipmentWatchService = shipmentWatchService;
        this.documentRequestService = documentRequestService;
        this.feedbackLoopService = feedbackLoopService;
        this.settingsService = settingsService;
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
        this.documentAttachedCounter = Counter.builder("smartfreight_document_requests_total")
                .tag("result", "attached")
                .description("Document requests resolved by the Phase 2.7 pipeline")
                .register(meterRegistry);
        this.documentEscalatedCounter = Counter.builder("smartfreight_document_requests_total")
                .tag("result", "escalated")
                .description("Document requests resolved by the Phase 2.7 pipeline")
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
                    // Phase 1.6: the SMTP send happens strictly AFTER the pipeline transaction
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
                    // Phase 1.7: a circuit breaker is OPEN — the dependency (Ollama) is known to
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
     * @return whether the ticket reached DRAFT_READY and qualifies for auto-send (Phase 1.6) —
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

        // Phase 1.3 session 3: classification happens here, off the IMAP thread.
        // Phase 1.4: the same call also assigns the business type (RFQ/STATUS_UPDATE/…).
        // Phase 1.7: in typed mode the retry path re-enters classification when ticketType
        // is still null — such a ticket never got a verdict (it was parked by an open
        // breaker before step 0 completed, or swept out of RECEIVED). This is a first
        // classification, not a re-judgement: tickets the operator sent back for a redraft
        // carry their type and are not re-classified. Binary mode persists no verdict
        // marker, so there the retry path keeps its historical fail-open skip.
        if (ticket.getStatus() == TicketStatus.RECEIVED
                || (ticket.getTicketType() == null && emailClassificationService.isTypedMode())) {
            log.info("Step 0: Classifying email relevance and type via Qwen 2.5");
            EmailClassificationService.ClassificationResult classification =
                    emailClassificationService.classify(ticket.getSubject(), buildAiInput(ticket));
            if (!classification.relevant()) {
                ticket.setStatus(TicketStatus.IRRELEVANT);
                // Phase 1.5: spam carries no SLA — drop the provisional deadline set at ingest.
                ticket.setSlaDeadlineAt(null);
                ticket.setUpdatedAt(LocalDateTime.now());
                emailTicketRepository.save(ticket);
                log.info("Ticket classified as IRRELEVANT — pipeline stopped, kept for audit");
                return false;
            }
            // Persisted together with the PROCESSING transition below — no extra UPDATE.
            ticket.setTicketType(classification.type());
            // Phase 1.5: refine the provisional (GENERAL) deadline to the per-type window.
            // Still anchored to createdAt — the customer's clock started at receipt.
            if (slaService.isEnabled()) {
                ticket.setSlaDeadlineAt(slaService.deadlineFor(classification.type(), ticket.getCreatedAt()));
            }
        }

        ticket.setStatus(TicketStatus.PROCESSING);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.saveAndFlush(ticket);

        // Phase 1.1: the AI sees the email body plus any parsed attachment text as one input.
        String aiInput = buildAiInput(ticket);

        log.info("Step 1/3: Extracting item identifier via Qwen 2.5");
        String identifier = aiExtractionService.extractIdentifier(aiInput);
        ticket.setExtractedIdentifier(identifier.isEmpty() ? null : identifier);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);

        log.info("Step 2/3: Looking up item '{}' via backend", identifier);
        Optional<TrackedItem> itemOpt = identifier.isEmpty()
                ? Optional.empty()
                : itemLookupService.findByIdentifier(identifier);

        TrackedItem item = itemOpt.orElse(null);
        if (item == null) {
            log.warn("Item '{}' not found — draft will reflect missing data", identifier);
        }

        log.info("Step 3/3: Generating reply draft via Llama 3.1");
        // Phase 1.2: matched carrier rates (if any) let the model quote a real price.
        String rateContext = rateSheetService.buildRateContext(aiInput);

        String draft;
        // Phase 2.7: a document request takes a dedicated path — determine which document is wanted,
        // attach it if on file (it then rides the same auto-send/operator gate as any reply), else
        // escalate: draft an acknowledgement and flag the requested type for the operator. The
        // detection call propagates an open-breaker exception, so a parked ticket re-runs cleanly.
        if (ticket.getTicketType() == TicketType.DOCUMENT_REQUEST
                && settingsService.isFeatureEnabled(DocumentService.FEATURE_KEY, true)) {
            DocumentType requested = documentRequestService.detectRequestedType(aiInput);
            ticket.setRequestedDocType(requested);
            Optional<TenantDocument> doc = identifier.isEmpty()
                    ? Optional.empty()
                    : documentRequestService.findDocument(identifier, requested);
            if (doc.isPresent()) {
                ticket.setAttachedDocumentId(doc.get().getId());
                draft = aiDraftingService.generateDocumentReply(aiInput, requested, item, true);
                documentAttachedCounter.increment();
                log.info("Document request: attaching {} for '{}'", requested, identifier);
            } else {
                draft = aiDraftingService.generateDocumentReply(aiInput, requested, item, false);
                documentEscalatedCounter.increment();
                log.info("Document request: {} not on file for '{}' — escalating to operator",
                        requested, identifier);
            }
        } else {
            // Phase 3.1 S2: inject the most similar past operator corrections (this tenant, this
            // type) as few-shot examples so the draft improves as corrections accumulate. Auxiliary
            // and graceful — retrieval failure returns "" and never blocks drafting.
            String exampleContext = feedbackLoopService.retrieveExamples(ticket.getTicketType(), aiInput);
            draft = aiDraftingService.generateDraft(aiInput, item, rateContext, exampleContext);
        }
        ticket.setDraftReply(draft);
        // Phase 3.1: preserve the unmodified AI draft so the feedback loop can compare it against
        // what the operator actually sends. draftReply gets overwritten on edit; this does not.
        ticket.setAiOriginalDraft(draft);

        // Phase 1.6: confidence is computed and stored on every run (even with auto-send off)
        // so the score distribution accumulates for threshold tuning.
        ticket.setAiConfidence(autoSendService.confidenceFor(
                ticket.getTicketType(),
                ticket.getExtractedIdentifier() != null,
                item != null,
                rateContext != null && !rateContext.isBlank()));

        ticket.setStatus(TicketStatus.DRAFT_READY);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);

        draftReadyCounter.increment();
        processingTimer.record(System.nanoTime() - startTime, java.util.concurrent.TimeUnit.NANOSECONDS);
        log.info("Ticket processing finished: DRAFT_READY (confidence {})", ticket.getAiConfidence());

        // Phase 2.4: a STATUS_UPDATE enquiry grounded in real backend data becomes a proactive
        // subscription — StatusWatchScheduler keeps this customer posted about this shipment
        // until it is delivered. Recorded unconditionally (cheap); the scheduler is gated by
        // feature.status-update-automation, so enabling the flag activates recent enquiries at once.
        if (ticket.getTicketType() == TicketType.STATUS_UPDATE
                && ticket.getExtractedIdentifier() != null
                && item != null) {
            shipmentWatchService.upsertFromTicket(ticket, item);
        }

        // The auto-send decision is made here (one settings read, one log line), but the SMTP
        // call belongs to the caller — after this transaction commits. An eligible ticket also
        // skips the Gmail draft: it is about to be answered, a lingering draft would be noise.
        boolean autoSendEligible = autoSendService.shouldAutoSend(ticket);
        if (!autoSendEligible) {
            gmailDraftService.saveDraft(ticket);
        }
        return autoSendEligible;
    }

    /**
     * Builds the text the AI models work on: the email body plus any parsed attachment text
     * (Phase 1.1). When the email has no attachments this is just the body, preserving the
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
     * Parks a ticket as RETRY_LATER because a circuit breaker rejected a pipeline call
     * (Phase 1.7). Runs in its own transaction for the same reason as {@link #markFailed}:
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
