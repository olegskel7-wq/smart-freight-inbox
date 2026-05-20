package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin API controller for maintenance operations.
 *
 * Access is restricted to OPERATOR role via SecurityConfig.
 * — (future) data cleanup, manual pipeline restart, etc.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final EmailTicketRepository emailTicketRepository;
    private final TicketProcessingService ticketProcessingService;
    private final TaskExecutor taskExecutor;

    public AdminController(EmailTicketRepository emailTicketRepository,
                           TicketProcessingService ticketProcessingService,
                           @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketProcessingService = ticketProcessingService;
        this.taskExecutor = taskExecutor;
    }

    /**
     * POST /admin/reprocess — reprocess all stuck / failed tickets.
     *
     * Used when:
     * 1. Ollama was unavailable and many tickets transitioned to FAILED.
     * 2. After Ollama recovery, those tickets need to be reprocessed.
     * 3. An operator rejected a draft (RETRY_LATER) and the AI needs to run again.
     *
     * Algorithm:
     * 1. Reject requests from non-localhost addresses (403 Forbidden).
     * 2. Bulk FAILED → NEW reset in a separate short transaction.
     * 3. Retrieve ONLY ticket IDs with NEW and RETRY_LATER statuses (projection).
     * 4. Dispatch each ticket to the application thread pool asynchronously.
     *    Each process() runs in its own transaction (REQUIRES_NEW),
     *    so HikariCP connections are released after each ticket.
     * 5. Return the number of queued tickets.
     *
     * Architectural note: the method has NO @Transactional,
     * because one "fat" transaction for the entire loop would hold a HikariCP connection
     * for the entire Ollama wait time (up to 150 s × number of tickets).
     *
     * @param request HTTP request used to verify the caller is localhost
     * @return 200 OK with the number of reprocessed tickets, or 403 if not localhost
     */
    @PostMapping("/reprocess")
    public ResponseEntity<String> reprocessAll(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!"127.0.0.1".equals(remoteAddr) && !"::1".equals(remoteAddr)) {
            log.warn("Rejected /admin/reprocess from non-localhost address: {}", remoteAddr);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Forbidden: admin endpoint is only accessible from localhost");
        }
        int reset = ticketProcessingService.prepareForRetry();

        List<Long> ids = emailTicketRepository.findIdsByStatusIn(
                List.of(TicketStatus.NEW, TicketStatus.RETRY_LATER));

        if (ids.isEmpty()) {
            return ResponseEntity.ok("No tickets to reprocess (reset " + reset + " from FAILED).");
        }

        log.info("Async reprocessing queued for {} ticket(s) (reset {} from FAILED)", ids.size(), reset);
        ids.forEach(id -> taskExecutor.execute(() -> ticketProcessingService.process(id)));

        return ResponseEntity.ok("Queued " + ids.size() + " tickets for reprocessing (reset " + reset + " from FAILED).");
    }
}
