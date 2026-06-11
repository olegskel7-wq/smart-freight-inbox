package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Runtime sweep for tickets stuck in RECEIVED / NEW / PROCESSING (Phase 1.3, session 2).
 *
 * Why this exists when {@link com.smartfreight.inbox.startup.StuckTicketRecovery}
 * already runs at startup: that recovery only fires on a JVM restart. A RUNNING
 * instance can still strand tickets:
 * — the pipeline message was poison and landed in the DLQ → the ticket stays NEW
 *   forever (no consumer will ever pick it up again);
 * — a consumer died mid-pipeline without taking the JVM down → the redelivered
 *   message is skipped by the PROCESSING status guard and the ticket hangs.
 *
 * Mechanism: any NEW/PROCESSING ticket whose updatedAt is older than
 * {@code sweep.stuck-after-minutes} is flipped to RETRY_LATER in one bulk UPDATE.
 * From there the existing {@link RetryScheduler} machinery takes over on its
 * normal cadence: retryCount increment (bounded by MAX_RETRIES), outbox event,
 * RabbitMQ consumer. The sweeper deliberately publishes nothing itself — one
 * unsticking path, not two.
 *
 * The threshold must comfortably exceed the worst-case time a HEALTHY ticket can
 * sit in NEW/PROCESSING (queue backlog wait + one pipeline run). Sweeping a ticket
 * whose original message is still queued is harmless — the idempotent consumer and
 * the optimistic-lock retry absorb the duplicate — but it wastes a retryCount
 * increment, so the default is a generous 30 minutes.
 */
@Component
public class StuckTicketSweeper {

    private static final Logger log = LoggerFactory.getLogger(StuckTicketSweeper.class);

    /**
     * Statuses a ticket can silently hang in when its pipeline message is lost.
     * RECEIVED (Phase 1.3 session 3): a ticket whose "ticket.created" message went poison
     * before classification. Sweeping sends it down the RETRY_LATER → NEW path, which
     * skips classification — intentional fail-open, same as a classifier outage.
     */
    static final List<TicketStatus> SWEEPABLE_STATUSES =
            List.of(TicketStatus.RECEIVED, TicketStatus.NEW, TicketStatus.PROCESSING);

    private final EmailTicketRepository emailTicketRepository;
    private final Counter sweptCounter;

    @Value("${sweep.stuck-after-minutes:30}")
    private long stuckAfterMinutes;

    public StuckTicketSweeper(EmailTicketRepository emailTicketRepository,
                              MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.sweptCounter = Counter.builder("smartfreight_tickets_swept_total")
                .description("Stuck RECEIVED/NEW/PROCESSING tickets moved to RETRY_LATER by the sweeper")
                .register(meterRegistry);
    }

    /**
     * fixedDelay 5 min: stuck detection is not latency-sensitive (the threshold is
     * tens of minutes anyway), and one bulk UPDATE per run keeps the cost trivial.
     */
    @Scheduled(fixedDelayString = "${sweep.poll-interval-ms:300000}", initialDelay = 120_000)
    @Transactional
    public void sweepStuckTickets() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(stuckAfterMinutes);

        int swept = emailTicketRepository.bulkTransitionStaleStatus(
                SWEEPABLE_STATUSES, TicketStatus.RETRY_LATER, cutoff);

        if (swept > 0) {
            sweptCounter.increment(swept);
            log.warn("StuckTicketSweeper: {} ticket(s) stuck in RECEIVED/NEW/PROCESSING for over {} min " +
                    "moved to RETRY_LATER", swept, stuckAfterMinutes);
        }
    }
}
