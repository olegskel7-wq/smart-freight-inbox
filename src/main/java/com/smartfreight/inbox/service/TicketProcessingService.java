package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Container;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
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
 * Coordinates three steps:
 * 1. Container number extraction (Qwen 2.5) from the email body
 * 2. Container lookup in the port-service system
 * 3. Reply draft generation (Llama 3.1)
 *
 * Executed synchronously in the calling thread
 * ({@link com.smartfreight.inbox.integration.EmailIngestionService} / {@link com.smartfreight.inbox.scheduler.RetryScheduler}).
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
    private final AiExtractionService aiExtractionService;
    private final ItemLookupService itemLookupService;
    private final AiDraftingService aiDraftingService;
    private final GmailDraftService gmailDraftService;
    private final Timer processingTimer;
    private final Counter draftReadyCounter;
    private final Counter failedCounter;

    /**
     * Self-reference to the Spring proxy so that @Transactional on processAttempt()
     * and markFailed() is honoured on intra-class calls.
     * @Lazy breaks the circular-dependency cycle during context startup.
     */
    @Autowired
    @Lazy
    private TicketProcessingService self;

    public TicketProcessingService(EmailTicketRepository emailTicketRepository,
                                   AiExtractionService aiExtractionService,
                                   ItemLookupService itemLookupService,
                                   AiDraftingService aiDraftingService,
                                   GmailDraftService gmailDraftService,
                                   MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.aiExtractionService = aiExtractionService;
        this.itemLookupService = itemLookupService;
        this.aiDraftingService = aiDraftingService;
        this.gmailDraftService = gmailDraftService;
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
                    self.processAttempt(ticketId, startTime);
                    return;
                } catch (ObjectOptimisticLockingFailureException e) {
                    log.warn("Optimistic lock conflict on attempt {}/{}, retrying",
                            attempt + 1, MAX_RETRY_ATTEMPTS);
                    if (attempt == MAX_RETRY_ATTEMPTS - 1) {
                        log.error("All retry attempts exhausted — marking ticket as FAILED");
                        self.markFailed(ticketId);
                    }
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
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processAttempt(Long ticketId, long startTime) {
        EmailTicket ticket = emailTicketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));

        if (ticket.getStatus() != TicketStatus.NEW && ticket.getStatus() != TicketStatus.RETRY_LATER) {
            log.warn("Ticket already in state {}, skipping", ticket.getStatus());
            return;
        }

        ticket.setStatus(TicketStatus.PROCESSING);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.saveAndFlush(ticket);

        log.info("Step 1/3: Extracting container number via Qwen 2.5");
        String containerNumber = aiExtractionService.extractContainerNumber(ticket.getBody());
        ticket.setExtractedContainerNumber(containerNumber.isEmpty() ? null : containerNumber);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);

        log.info("Step 2/3: Looking up container '{}' in database", containerNumber);
        Optional<Container> containerOpt = containerNumber.isEmpty()
                ? Optional.empty()
                : itemLookupService.findByIdentifier(containerNumber);

        Container container = containerOpt.orElse(null);
        if (container == null) {
            log.warn("Container '{}' not found — draft will reflect missing data", containerNumber);
        }

        log.info("Step 3/3: Generating reply draft via Llama 3.1");
        String draft = aiDraftingService.generateDraft(ticket.getBody(), container);
        ticket.setDraftReply(draft);

        ticket.setStatus(TicketStatus.DRAFT_READY);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);

        draftReadyCounter.increment();
        processingTimer.record(System.nanoTime() - startTime, java.util.concurrent.TimeUnit.NANOSECONDS);
        log.info("Ticket processing finished: DRAFT_READY");

        gmailDraftService.saveDraft(ticket);
    }

    /**
     * Marks a ticket as FAILED in its own transaction.
     *
     * Public and REQUIRES_NEW so it can be called through the Spring proxy from
     * {@link #process} after a failed attempt's transaction has already been rolled back.
     *
     * @param ticketId ticket identifier
     */
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
