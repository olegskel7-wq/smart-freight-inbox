package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin API controller for maintenance operations (localhost only).
 *
 * Guards "dangerous" operations for developers and administrators:
 * — Reprocessing failed / stuck tickets
 * — (future) data cleanup, manual pipeline restart, etc.
 *
 * Protection: all endpoints verify that the request originates from localhost (127.0.0.1 or ::1).
 * Assumption: in production this controller will not be reachable from outside
 * (firewall / reverse proxy does not expose the port externally).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final EmailTicketRepository emailTicketRepository;
    private final TicketProcessingService ticketProcessingService;

    public AdminController(EmailTicketRepository emailTicketRepository,
                           TicketProcessingService ticketProcessingService) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketProcessingService = ticketProcessingService;
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
     * 1. Verify that the request comes from localhost.
     * 2. Bulk FAILED → NEW reset in a separate short transaction.
     * 3. Retrieve ONLY ticket IDs with NEW and RETRY_LATER statuses (projection).
     * 4. Synchronously call process(id) for each ticket in the current thread.
     *    Each process() runs in its own transaction (REQUIRES_NEW),
     *    so HikariCP connections are released after each ticket.
     * 5. Return the number of reprocessed tickets.
     *
     * Architectural note: the method has NO @Transactional,
     * because one "fat" transaction for the entire loop would hold a HikariCP connection
     * for the entire Ollama wait time (up to 150 s × number of tickets).
     *
     * @param request HTTP request (needed to check remoteAddr)
     * @return 200 OK with the number of reprocessed tickets, or 403 if not localhost
     */
    @PostMapping("/reprocess")
    public ResponseEntity<String> reprocessAll(HttpServletRequest request) {
        if (!isLocalhost(request)) {
            log.warn("Rejected /admin/reprocess from remote address: {}", request.getRemoteAddr());
            return ResponseEntity.status(403).body("Forbidden: admin endpoints are local-only.");
        }

        int reset = ticketProcessingService.prepareForRetry();

        List<Long> ids = emailTicketRepository.findIdsByStatusIn(
                List.of(TicketStatus.NEW, TicketStatus.RETRY_LATER));

        if (ids.isEmpty()) {
            return ResponseEntity.ok("No tickets to reprocess (reset " + reset + " from FAILED).");
        }

        int count = ids.size();
        log.info("Synchronous reprocessing started for {} ticket(s) (reset {} from FAILED)", count, reset);
        ids.forEach(ticketProcessingService::process);
        log.info("Synchronous reprocessing finished for {} ticket(s)", count);

        return ResponseEntity.ok("Reprocessed " + count + " tickets (reset " + reset + " from FAILED).");
    }

    /**
     * Checks whether the request originated from the local machine.
     *
     * Supported formats:
     * — {@code 127.0.0.1} — IPv4 loopback
     * — {@code ::1} — IPv6 loopback (short form)
     * — {@code 0:0:0:0:0:0:0:1} — IPv6 loopback (full form)
     *
     * Note: if the application runs behind a reverse proxy (nginx/traefik),
     * configure {@code server.forward-headers-strategy=NATIVE}
     * or restrict access to /admin/** at the proxy level.
     *
     * @param request HTTP request
     * @return true if remoteAddr points to localhost, false otherwise
     */
    private boolean isLocalhost(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return "127.0.0.1".equals(addr) || "::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr);
    }
}
