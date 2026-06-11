package com.smartfreight.inbox.entity.enums;

/**
 * Business type of an incoming email (Phase 1.4 — extended classification).
 *
 * Assigned by the AI classifier in the same consumer step that decides relevance
 * (one Qwen call returns either IRRELEVANT or one of these types). The type is the
 * routing seam for downstream features: SLA deadlines per type (1.5), auto-send
 * thresholds (1.6), and eventually per-type workflows.
 *
 * Nullable on {@link com.smartfreight.inbox.entity.EmailTicket}: tickets created
 * before 1.4, tickets re-entering via the retry path (classification is not
 * repeated), and IRRELEVANT tickets have no type.
 */
public enum TicketType {

    /** Request for quotation — customer asks for a price/rate. */
    RFQ,

    /** Status enquiry — where is my shipment, ETA, tracking. */
    STATUS_UPDATE,

    /** Booking confirmation or booking request. */
    BOOKING_CONFIRM,

    /** Question about an invoice, charges or payment. */
    INVOICE_QUERY,

    /** Request for documents (POD, bill of lading, customs papers, etc.). */
    DOCUMENT_REQUEST,

    /** Relevant freight email that fits none of the specific types. Also the fail-open default. */
    GENERAL
}
