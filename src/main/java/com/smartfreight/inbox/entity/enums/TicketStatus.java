package com.smartfreight.inbox.entity.enums;

/**
 * Ticket lifecycle status enumeration.
 *
 * Transition diagram:
 *   NEW → PROCESSING → DRAFT_READY → CLOSED
 *                    ↘ FAILED
 *                    ↘ RETRY_LATER → NEW (via RetryScheduler)
 */
public enum TicketStatus {

    /** New email received, not yet processed. */
    NEW,

    /** Email is currently being processed (AI extracts container, looks up DB, generates reply). */
    PROCESSING,

    /** Draft is ready — the operator can review and send it. */
    DRAFT_READY,

    /** Processing failed (AI unavailable, parsing error, etc.). The operator must handle it manually. */
    FAILED,

    /** Transient error — retry scheduled. RetryScheduler will automatically pick up the ticket later. */
    RETRY_LATER,

    /** Ticket closed by the operator — reply sent or issue resolved without a reply. */
    CLOSED
}
