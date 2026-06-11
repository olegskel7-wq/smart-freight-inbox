package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.messaging.DlqAdminService;
import com.smartfreight.inbox.messaging.TicketEventPublisher;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administrative endpoints for ticket maintenance.
 *
 * Requires ROLE_OPERATOR (enforced by the Spring Security filter chain).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final EmailTicketRepository emailTicketRepository;
    private final TicketProcessingService ticketProcessingService;
    private final TicketEventPublisher ticketEventPublisher;
    private final DlqAdminService dlqAdminService;

    public AdminController(EmailTicketRepository emailTicketRepository,
                           TicketProcessingService ticketProcessingService,
                           TicketEventPublisher ticketEventPublisher,
                           DlqAdminService dlqAdminService) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketProcessingService = ticketProcessingService;
        this.ticketEventPublisher = ticketEventPublisher;
        this.dlqAdminService = dlqAdminService;
    }

    /**
     * POST /admin/reprocess — reprocess all stuck / failed tickets.
     *
     * Used when:
     * 1. Ollama was unavailable and many tickets transitioned to FAILED.
     * 2. After Ollama recovery, those tickets need to be reprocessed.
     * 3. An operator rejected a draft (RETRY_LATER) and the AI needs to run again.
     *
     * Algorithm (Phase 1.3, via the transactional outbox):
     * 1. Bulk FAILED → NEW reset.
     * 2. Retrieve ONLY ticket IDs with NEW and RETRY_LATER statuses (projection).
     * 3. Record a retry event per ticket in the outbox — same transaction as the reset,
     *    so "status reset" and "event recorded" are atomic.
     * 4. OutboxRelay → RabbitMQ → TicketEventConsumer does the actual AI work, with
     *    concurrency capped by the consumer (no Ollama stampede however many tickets).
     *
     * @Transactional is cheap here, unlike the pre-1.3 version: the method only does
     * two quick DB writes — all LLM waiting happens in the consumer, off this thread.
     *
     * @return 200 OK with the number of queued tickets
     */
    @PostMapping("/reprocess")
    @Transactional
    public ResponseEntity<String> reprocessAll() {
        int reset = ticketProcessingService.prepareForRetry();

        List<Long> ids = emailTicketRepository.findIdsByStatusIn(
                List.of(TicketStatus.NEW, TicketStatus.RETRY_LATER));

        if (ids.isEmpty()) {
            return ResponseEntity.ok("No tickets to reprocess (reset " + reset + " from FAILED).");
        }

        log.info("Async reprocessing queued for {} ticket(s) (reset {} from FAILED)", ids.size(), reset);
        ids.forEach(ticketEventPublisher::publishRetryRequested);

        return ResponseEntity.ok("Queued " + ids.size() + " tickets for reprocessing (reset " + reset + " from FAILED).");
    }

    /**
     * GET /admin/dlq — current dead-letter queue depth.
     *
     * Non-zero means poison messages: payloads the consumer rejected 3 times.
     * Inspect them in the RabbitMQ management UI (:15672), then either fix the
     * cause and requeue, or purge.
     */
    @GetMapping("/dlq")
    public ResponseEntity<String> dlqStatus() {
        return ResponseEntity.ok("DLQ contains " + dlqAdminService.messageCount() + " message(s).");
    }

    /**
     * POST /admin/dlq/requeue — move messages from the DLQ back to the work queue.
     *
     * Use after deploying a fix for whatever made them poison. Still-broken
     * messages bounce back to the DLQ after 3 attempts, so this is safe to call
     * speculatively. {@code limit} caps one invocation (default 100).
     */
    @PostMapping("/dlq/requeue")
    public ResponseEntity<String> dlqRequeue(@RequestParam(defaultValue = "100") int limit) {
        int moved = dlqAdminService.requeue(limit);
        return ResponseEntity.ok("Requeued " + moved + " message(s) from the DLQ.");
    }

    /**
     * POST /admin/dlq/purge — irreversibly drop all DLQ messages.
     *
     * The ticket rows behind them are NOT touched: a stuck ticket is later swept
     * to RETRY_LATER by StuckTicketSweeper, so purging never strands data —
     * PostgreSQL, not the broker, is the source of truth.
     */
    @PostMapping("/dlq/purge")
    public ResponseEntity<String> dlqPurge() {
        int purged = dlqAdminService.purge();
        return ResponseEntity.ok("Purged " + purged + " message(s) from the DLQ.");
    }
}
