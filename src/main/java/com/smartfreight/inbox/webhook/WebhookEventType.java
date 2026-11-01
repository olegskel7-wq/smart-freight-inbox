package com.smartfreight.inbox.webhook;

/**
 * The catalogue of outbound webhook event types.
 *
 * <p>This is the stable contract a tenant's CRM subscribes to — names are part of the public payload
 * ({@code type} field) and the subscription filter, so they are not renamed lightly.
 * {@link #TICKET_CREATED} fires on ingest; the semantic lifecycle events {@link #REPLY_SENT},
 * {@link #QUOTE_SENT} and {@link #BOOKING_CONFIRMED} are emitted at the relevant service sites.
 *
 * <p>The events are independent facts, not mutually-exclusive states (Stripe/GitHub semantics): a sent
 * RFQ reply fires BOTH {@link #REPLY_SENT} (every reply) and {@link #QUOTE_SENT} (the price specifically),
 * as separate deliveries with their own event ids — a subscriber filters to the ones it cares about.
 *
 * <p>A subscription's {@code event_types} is a CSV of these names, or {@value #ALL} to receive every type.
 */
public final class WebhookEventType {

    /** Wildcard in a subscription's event_types CSV — matches every event type. */
    public static final String ALL = "*";

    /** A new inbound email was ingested as a ticket (fires pre-classification, for every inbound). */
    public static final String TICKET_CREATED = "ticket.created";

    /** A reply was sent to the customer — operator send or auto-send, any ticket type. */
    public static final String REPLY_SENT = "reply.sent";

    /** An RFQ price reply (a quote) was sent — a {@link #REPLY_SENT} specialised to RFQ tickets. */
    public static final String QUOTE_SENT = "quote.sent";

    /** An inbound email was classified BOOKING_CONFIRM — the customer is booking/confirming. */
    public static final String BOOKING_CONFIRMED = "booking.confirmed";

    /** All known event types, for validating a subscription's filter and for the UI picker. */
    public static final java.util.List<String> ALL_TYPES = java.util.List.of(
            TICKET_CREATED, REPLY_SENT, QUOTE_SENT, BOOKING_CONFIRMED);

    private WebhookEventType() {
    }
}
