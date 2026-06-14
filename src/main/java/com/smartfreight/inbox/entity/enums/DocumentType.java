package com.smartfreight.inbox.entity.enums;

/**
 * Type of a freight document a customer can request or an operator can store (Phase 2.7).
 *
 * <p>Used in two places: the per-tenant {@code tenant_document} store tags each uploaded file
 * with one of these, and the AI classifier ({@link com.smartfreight.inbox.service.DocumentRequestService})
 * maps a DOCUMENT_REQUEST enquiry to one of these so the matching document can be looked up.
 *
 * <p>{@link #OTHER} is the fail-open default — used when the request is unclear or the model
 * returns something unrecognised, so the pipeline always has a valid value and degrades to
 * operator escalation rather than throwing.
 */
public enum DocumentType {

    /** Proof of delivery / delivery note. */
    POD("Proof of Delivery"),

    /** Bill of lading / B/L / sea waybill. */
    BILL_OF_LADING("Bill of Lading"),

    /** Customs paperwork — entry documents, C88, T1, clearance papers. */
    CUSTOMS("Customs Document"),

    /** A copy of an invoice or freight charges. */
    INVOICE_COPY("Invoice Copy"),

    /** Packing list / manifest of goods. */
    PACKING_LIST("Packing List"),

    /** CMR consignment note (road transport). */
    CMR("CMR Note"),

    /** Anything else, or an unclear request. Also the fail-open default. */
    OTHER("Document");

    private final String label;

    DocumentType(String label) {
        this.label = label;
    }

    /** Human-readable label for prompts and the operator UI. */
    public String getLabel() {
        return label;
    }
}
