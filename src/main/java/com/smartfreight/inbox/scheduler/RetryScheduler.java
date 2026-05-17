package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduler for reprocessing stuck tickets.
 *
 * Periodically (every 2 minutes) searches for tickets with RETRY_LATER status
 * and automatically restarts the AI pipeline.
 *
 * Use cases:
 * — Automatic retry on transient Ollama errors
 * — Reprocessing of drafts rejected by the operator (RETRY_LATER status)
 *
 * Pipeline:
 * 1. Find RETRY_LATER tickets with retryCount &lt; MAX_RETRIES (5)
 * 2. For each: increment retryCount, reset status to NEW
 * 3. Invoke TicketProcessingService.process() synchronously
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
     * Maximum tickets processed per scheduler run.
     * Prevents overloading Ollama when many tickets accumulate (each LLM call takes up to 150 s).
     * Configurable via {@code retry.batch-size} (default: 10).
     */
    @Value("${retry.batch-size:10}")
    private int batchSize = 10;

    private final EmailTicketRepository emailTicketRepository;
    private final TicketProcessingService ticketProcessingService;
    private final TaskExecutor taskExecutor;
    private final Counter retryCounter;

    public RetryScheduler(EmailTicketRepository emailTicketRepository,
                          TicketProcessingService ticketProcessingService,
                          @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                          MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketProcessingService = ticketProcessingService;
        this.taskExecutor = taskExecutor;
        this.retryCounter = Counter.builder("smartfreight_tickets_retry_count_total")
                .description("Total number of retry processing attempts for tickets")
                .register(meterRegistry);
    }

    /**
     * Periodically executed task to reprocess stuck tickets.
     *
     * Schedule:
     * — {@code fixedDelay = 120_000 ms} (2 minutes) — pause BETWEEN the end of one execution and the start of the next
     * — {@code initialDelay = 60_000 ms} (1 minute) — pause before the FIRST execution
     *   (gives the application time to initialize after startup)
     *
     * fixedDelay is used instead of fixedRate to avoid accumulated concurrent executions:
     * — fixedRate: fires every 2 minutes regardless of method duration
     * — fixedDelay: if the method ran for 5 minutes, the next run starts 2 minutes after it finishes
     *
     * Ollama may take up to 150 s per ticket, so fixedDelay is more appropriate.
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    public void retryFailedTickets() {
        List<EmailTicket> allEligible = emailTicketRepository
                .findByStatusAndRetryCountLessThan(TicketStatus.RETRY_LATER, MAX_RETRIES);

        if (allEligible.isEmpty()) {
            return;
        }

        List<EmailTicket> toRetry = allEligible.size() > batchSize
                ? allEligible.subList(0, batchSize)
                : allEligible;

        log.info("RetryScheduler: found {} eligible ticket(s), processing {} (batch limit {})",
                allEligible.size(), toRetry.size(), batchSize);

        for (EmailTicket ticket : toRetry) {
            ticket.setRetryCount(ticket.getRetryCount() + 1);
            ticket.setStatus(TicketStatus.NEW);
            emailTicketRepository.save(ticket);

            log.info("Retrying ticket ID={} (attempt {} of {})",
                    ticket.getId(), ticket.getRetryCount(), MAX_RETRIES);

            retryCounter.increment();
            final Long ticketId = ticket.getId();
            taskExecutor.execute(() -> ticketProcessingService.process(ticketId));
        }
    }
}
