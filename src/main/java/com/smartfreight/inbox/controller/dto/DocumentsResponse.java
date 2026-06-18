package com.smartfreight.inbox.controller.dto;

import java.util.List;

/**
 * Snapshot of the current tenant's document store for the React {@code /documents} page (Phase 3.2).
 * {@code featureEnabled} mirrors {@code feature.document-request}: when off, document enquiries fall
 * back to the generic reply, so the SPA shows a warning badge. {@code docTypes} feeds the upload
 * type dropdown (enum name + human label) so the client never hard-codes the {@code DocumentType} list.
 */
public record DocumentsResponse(
        boolean featureEnabled,
        List<DocumentRefDto> documents,
        List<DocTypeOption> docTypes
) {
    /** One selectable document type: the enum {@code value} to POST and the {@code label} to show. */
    public record DocTypeOption(String value, String label) {}
}
