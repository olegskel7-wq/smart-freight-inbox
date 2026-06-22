package com.smartfreight.inbox.webhook;

/**
 * The catalogue of outbound webhook event types (Phase 3.5).
 *
 * <p>This is the stable contract a tenant's CRM subscribes to — names are part of the public payload
 * ({@code type} field) and the subscription filter, so they are not renamed lightly. S1 ships
 * {@link #TICKET_CREATED}; the hybrid event source means richer semantic events
 * ({@code reply.sent}, {@code quote.sent}, {@code booking.confirmed}) are added in S2 by emitting at
 * the relevant service sites and listing them here.
 *
 * <p>A subscription's {@code event_types} is a CSV of these names, or {@value #ALL} to receive every type.
 */
public final class WebhookEventType {

    /** Wildcard in a subscription's event_types CSV — matches every event type. */
    public static final String ALL = "*";

    /** A new inbound email was ingested as a ticket (fires pre-classification, for every inbound). */
    public static final String TICKET_CREATED = "ticket.created";

    /** All known event types, for validating a subscription's filter and for the UI picker. */
    public static final java.util.List<String> ALL_TYPES = java.util.List.of(
            TICKET_CREATED);

    private WebhookEventType() {
    }
}
