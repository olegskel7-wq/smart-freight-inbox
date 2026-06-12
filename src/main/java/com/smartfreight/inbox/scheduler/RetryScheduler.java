package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.messaging.TicketEventPublisher;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Scheduler for reprocessing stuck tickets.
 *
 * Periodically (every 2 minutes) searches for tickets with RETRY_LATER status
 * and re-enqueues them for the AI pipeline via the transactional outbox (Phase 1.3).
 *
 * Use cases:
 * — Automatic retry on transient Ollama errors
 * — Reprocessing of drafts rejected by the operator (RETRY_LATER status)
 *
 * Pipeline:
 * 1. Find RETRY_LATER tickets with retryCount &lt; MAX_RETRIES (5)
 * 2. For each: increment retryCount, reset status to NEW, write a
 *    "ticket.retry-requested" outbox event — all in one transaction
 * 3. OutboxRelay publishes the event; TicketEventConsumer runs the pipeline
 * 4. Success → DRAFT_READY, failure → RETRY_LATER again or FAILED
 *
 * MAX_RETRIES protects against infinite loops with "broken" tickets.
 */
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    /**
     * Maximum number of processing attempts per ticket.
     * After this the ticket remains RETRY_LATER — the operator must handle it manually.
     */
    private static final int MAX_RETRIES = 5;

    /**
     * Maximum tickets re-enqueued per scheduler run.
     * Prevents flooding the queue when many tickets accumulate; actual concurrency
     * towards Ollama is capped by the RabbitMQ consumer (2 consumers, prefetch=1).
     * Configurable via {@code retry.batch-size} (default: 10).
     */
    @Value("${retry.batch-size:10}")
    private int batchSize = 10;

    private final EmailTicketRepository emailTicketRepository;
    private final TicketEventPublisher ticketEventPublisher;
    private final CircuitBreaker ollamaCircuitBreaker;
    private final Counter retryCounter;

    public RetryScheduler(EmailTicketRepository emailTicketRepository,
                          TicketEventPublisher ticketEventPublisher,
                          @Qualifier("ollamaCircuitBreaker") CircuitBreaker ollamaCircuitBreaker,
                          MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketEventPublisher = ticketEventPublisher;
        this.ollamaCircuitBreaker = ollamaCircuitBreaker;
        this.retryCounter = Counter.builder("smartfreight_tickets_retry_count_total")
                .description("Total number of retry processing attempts for tickets")
                .register(meterRegistry);
    }

    /**
     * Periodically executed task to re-enqueue stuck tickets.
     *
     * Schedule:
     * — {@code fixedDelay = 120_000 ms} (2 minutes) — pause BETWEEN the end of one execution and the start of the next
     * — {@code initialDelay = 60_000 ms} (1 minute) — pause before the FIRST execution
     *   (gives the application time to initialize after startup)
     *
     * @Transactional — the status reset and the outbox event are atomic per run:
     * a ticket can never be reset to NEW without its retry event being recorded,
     * and vice versa. The method itself is fast (no LLM calls) — heavy lifting
     * happens in the RabbitMQ consumer.
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    @Transactional
    public void retryFailedTickets() {
        // Phase 1.7: while the Ollama breaker is OPEN every pipeline run would be parked
        // straight back to RETRY_LATER. Skipping the whole run keeps retryCount intact —
        // the retry budget is spent on real attempts, not on a dependency known to be down.
        // In HALF_OPEN the run proceeds: the first re-enqueued ticket is the probe call.
        CircuitBreaker.State ollamaState = ollamaCircuitBreaker.getState();
        if (ollamaState == CircuitBreaker.State.OPEN || ollamaState == CircuitBreaker.State.FORCED_OPEN) {
            log.info("RetryScheduler: ollama circuit breaker is {} — skipping this run", ollamaState);
            return;
        }

        List<EmailTicket> allEligible = emailTicketRepository
                .findByStatusAndRetryCountLessThan(TicketStatus.RETRY_LATER, MAX_RETRIES);

        if (allEligible.isEmpty()) {
            return;
        }

        List<EmailTicket> toRetry = allEligible.size() > batchSize
                ? allEligible.subList(0, batchSize)
                : allEligible;

        log.info("RetryScheduler: found {} eligible ticket(s), re-enqueueing {} (batch limit {})",
                allEligible.size(), toRetry.size(), batchSize);

        for (EmailTicket ticket : toRetry) {
            ticket.setRetryCount(ticket.getRetryCount() + 1);
            ticket.setStatus(TicketStatus.NEW);
            emailTicketRepository.save(ticket);

            log.info("Re-enqueueing ticket ID={} (attempt {} of {})",
                    ticket.getId(), ticket.getRetryCount(), MAX_RETRIES);

            retryCounter.increment();
            ticketEventPublisher.publishRetryRequested(ticket.getId());
        }
    }
}
