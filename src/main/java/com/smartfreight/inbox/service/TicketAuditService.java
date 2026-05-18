package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TicketAuditLog;
import com.smartfreight.inbox.repository.TicketAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin facade for writing {@link TicketAuditLog} entries.
 *
 * <p>Uses {@code Propagation.REQUIRES_NEW} so an audit insert never rolls back
 * with the caller's transaction — losing an audit row because the SMTP send failed
 * after the row was inserted is acceptable; losing the original action because the
 * audit insert failed is not.
 *
 * <p>The actor email is resolved from {@link SecurityContextHolder} so callers
 * don't have to thread {@code Authentication} through every layer.
 */
@Service
public class TicketAuditService {

    private static final Logger log = LoggerFactory.getLogger(TicketAuditService.class);

    /** Action codes — kept as constants to avoid magic strings at call sites. */
    public static final String ACTION_SEND       = "SEND";
    public static final String ACTION_CLOSE      = "CLOSE";
    public static final String ACTION_DELETE     = "DELETE";
    public static final String ACTION_DRAFT_EDIT = "DRAFT_EDIT";

    private final TicketAuditLogRepository repository;

    public TicketAuditService(TicketAuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Records an action against a ticket. Never throws — audit failures are logged
     * and swallowed so they cannot break the user flow.
     *
     * @param ticketId target ticket
     * @param action   one of the {@code ACTION_*} constants
     * @param details  optional context (recipient, error message, etc.); may be {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long ticketId, String action, String details) {
        try {
            TicketAuditLog entry = TicketAuditLog.builder()
                    .ticketId(ticketId)
                    .actorEmail(currentActorEmail())
                    .action(action)
                    .details(details)
                    .build();
            repository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to write audit entry (ticket={}, action={}): {}",
                    ticketId, action, e.getMessage());
        }
    }

    /**
     * Resolves the email of the currently authenticated principal, or {@code "system"}
     * when called outside an HTTP request (e.g. from the AI pipeline).
     */
    private String currentActorEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "system";
        }
        return auth.getName();
    }
}
