package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @PostMapping("/reprocess")
    @Transactional
    public ResponseEntity<String> reprocessAll(HttpServletRequest request) {
        // Fix 1: restrict to localhost only
        if (!isLocalhost(request)) {
            log.warn("Rejected /admin/reprocess from remote address: {}", request.getRemoteAddr());
            return ResponseEntity.status(403).body("Forbidden: admin endpoints are local-only.");
        }

        List<EmailTicket> pending = emailTicketRepository.findAll().stream()
                .filter(t -> t.getStatus() == TicketStatus.NEW
                          || t.getStatus() == TicketStatus.FAILED
                          || t.getStatus() == TicketStatus.RETRY_LATER)
                .toList();

        if (pending.isEmpty()) {
            return ResponseEntity.ok("No tickets to reprocess.");
        }

        // Reset FAILED → NEW so TicketProcessingService accepts them
        pending.forEach(t -> {
            if (t.getStatus() == TicketStatus.FAILED) {
                t.setStatus(TicketStatus.NEW);
                t.setRetryCount(0);
                emailTicketRepository.save(t);
            }
        });

        List<Long> ids = pending.stream().map(EmailTicket::getId).toList();
        int count = ids.size();

        log.info("Synchronous reprocessing started for {} tickets", count);
        ids.forEach(ticketProcessingService::process);
        log.info("Synchronous reprocessing finished for {} tickets", count);

        return ResponseEntity.ok("Reprocessed " + count + " tickets.");
    }

    private boolean isLocalhost(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return "127.0.0.1".equals(addr) || "::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr);
    }
}
