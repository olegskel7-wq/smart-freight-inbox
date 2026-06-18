package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.entity.TenantDocument;

import java.time.LocalDateTime;

/**
 * Metadata view of a {@link TenantDocument} for the ticket detail API (Phase 3.2) — never the
 * file bytes. Used both for the currently attached document and the documents available to attach.
 */
public record DocumentRefDto(
        Long id,
        String identifier,
        String docType,
        String filename,
        String contentType,
        long fileSize,
        LocalDateTime uploadedAt
) {
    public static DocumentRefDto from(TenantDocument doc) {
        return new DocumentRefDto(
                doc.getId(),
                doc.getIdentifier(),
                doc.getDocType() == null ? null : doc.getDocType().name(),
                doc.getFilename(),
                doc.getContentType(),
                doc.getFileSize(),
                doc.getUploadedAt()
        );
    }
}
