package com.smartfreight.inbox.webhook;

import com.smartfreight.inbox.entity.EmailTicket;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@code data} block of a ticket-related webhook envelope (Phase 3.5).
 *
 * <p>One shared shape across every ticket event ({@code ticket.created}, {@code reply.sent},
 * {@code quote.sent}, {@code booking.confirmed}) so a subscriber sees the same fields regardless of which
 * event fired — only {@code status}/{@code ticket_type} differ by the ticket's state at emit time.
 * {@code ticket_type} is null for {@code ticket.created} (it fires pre-classification). Kept out of
 * {@link WebhookEventPublisher} so the publisher stays decoupled from the entity model.
 */
public final class WebhookPayloads {

    private WebhookPayloads() {
    }

    /** The stable {@code data} fields for a ticket event: id, subject, sender, status, type, received_at. */
    public static Map<String, Object> ticketData(EmailTicket ticket) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticket_id", ticket.getId());
        data.put("subject", ticket.getSubject());
        data.put("sender_email", ticket.getSenderEmail());
        data.put("status", ticket.getStatus() != null ? ticket.getStatus().name() : null);
        data.put("ticket_type", ticket.getTicketType() != null ? ticket.getTicketType().name() : null);
        data.put("received_at", ticket.getCreatedAt() != null ? ticket.getCreatedAt().toString() : null);
        return data;
    }
}
