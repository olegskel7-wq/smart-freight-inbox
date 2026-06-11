package com.smartfreight.inbox.entity.enums;

/**
 * Ticket lifecycle status enumeration.
 *
 * Transition diagram:
 *   RECEIVED → IRRELEVANT                              (classifier rejected — terminal)
 *   RECEIVED → PROCESSING → DRAFT_READY → CLOSED
 *                         ↘ FAILED
 *                         ↘ RETRY_LATER → NEW → PROCESSING (via RetryScheduler)
 *
 * Since Phase 1.3 session 3 a freshly ingested email lands as RECEIVED — persisted but
 * not yet classified. The RabbitMQ consumer runs LLM classification off the IMAP thread:
 * IRRELEVANT for non-freight mail, otherwise straight into the AI pipeline (PROCESSING).
 * NEW is the re-entry point for retries — classification already happened (or is
 * deliberately skipped fail-open when a swept RECEIVED ticket re-enters via NEW).
 */
public enum TicketStatus {

    /** Email persisted by ingestion, not yet classified. Consumer decides: IRRELEVANT or pipeline. */
    RECEIVED,

    /** Ready for the AI pipeline (already past classification). Re-entry point for retries. */
    NEW,

    /** Email is currently being processed (AI extracts container, looks up DB, generates reply). */
    PROCESSING,

    /** Draft is ready — the operator can review and send it. */
    DRAFT_READY,

    /** Processing failed (AI unavailable, parsing error, etc.). The operator must handle it manually. */
    FAILED,

    /** Transient error — retry scheduled. RetryScheduler will automatically pick up the ticket later. */
    RETRY_LATER,

    /** Classifier judged the email non-freight (Phase 1.3 session 3). Terminal; kept for audit/review. */
    IRRELEVANT,

    /** Ticket closed by the operator — reply sent or issue resolved without a reply. */
    CLOSED
}
