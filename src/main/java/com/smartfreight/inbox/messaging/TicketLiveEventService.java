package com.smartfreight.inbox.messaging;

import com.smartfreight.inbox.entity.EmailTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Broadcasts {@link TicketLiveEvent}s to the per-tenant ticket topic over STOMP (Phase 3.2).
 *
 * <p>Invoked from {@link TicketLiveEventListener} on every ticket persist/update/soft-delete. The
 * broadcast is deferred to <b>after the surrounding transaction commits</b> — the same discipline as
 * the Phase 1.6 SMTP send: an event must describe a state that is actually durable, so a rolled-back
 * attempt (optimistic-lock retry, pipeline failure) never pushes a phantom change to operators. When
 * there is no active transaction (rare — most writes are transactional) the event is sent inline.
 *
 * <p>The tenant is read straight off the entity ({@code tenant_id}, stamped by Hibernate before the
 * lifecycle callback fires) — no {@link com.smartfreight.inbox.tenancy.TenantContext} lookup needed.
 * Rows stamped with a non-tenant sentinel (system/anonymous writes) are skipped: there is no operator
 * topic for them.
 */
@Service
public class TicketLiveEventService {

    private static final Logger log = LoggerFactory.getLogger(TicketLiveEventService.class);

    /** Per-tenant ticket topic prefix — must match {@code StompAuthChannelInterceptor}. */
    private static final String TOPIC_PREFIX = "/topic/tickets/";

    private final SimpMessagingTemplate messagingTemplate;

    public TicketLiveEventService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /** Snapshots the ticket and broadcasts a change event to its tenant's topic after commit. */
    public void broadcast(EmailTicket ticket) {
        Long tenantId = ticket.getTenantId();
        // Positive ids only — skip the system (-1) / anonymous (-2) sentinels, which have no topic.
        if (tenantId == null || tenantId <= 0) {
            return;
        }
        // Snapshot now, while the entity is safely readable; the deferred send touches no entity.
        TicketLiveEvent event = new TicketLiveEvent(
                ticket.getId(),
                ticket.getStatus() == null ? null : ticket.getStatus().name());
        String destination = TOPIC_PREFIX + tenantId;

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(destination, event);
                }
            });
        } else {
            send(destination, event);
        }
    }

    private void send(String destination, TicketLiveEvent event) {
        try {
            messagingTemplate.convertAndSend(destination, event);
        } catch (RuntimeException e) {
            // A real-time push is auxiliary — never let a broker hiccup affect the business write.
            log.warn("Failed to broadcast ticket event {} to {}: {}", event, destination, e.getMessage());
        }
    }
}
