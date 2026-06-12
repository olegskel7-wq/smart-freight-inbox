package com.smartfreight.inbox.messaging;

import java.time.Instant;

/**
 * Wire format of a ticket event (JSON in the outbox payload and on the broker).
 *
 * Deliberately minimal — "pass ID, not entity": the consumer reloads the ticket
 * from the DB, so the message can never carry stale ticket state.
 *
 * @param ticketId   id of the ticket to process
 * @param tenantId   tenant the ticket belongs to (Phase 2.2) — the consumer binds its
 *                   TenantContext to it before processing, so the reload itself is
 *                   tenant-scoped. Null on pre-2.2 in-flight messages → default tenant.
 * @param eventType  one of the {@code TYPE_*} constants; doubles as the routing key
 * @param occurredAt when the event was recorded (business-transaction time)
 */
public record TicketEventMessage(Long ticketId, Long tenantId, String eventType, Instant occurredAt) {

    public static final String TYPE_TICKET_CREATED = "ticket.created";
    public static final String TYPE_RETRY_REQUESTED = "ticket.retry-requested";
}
