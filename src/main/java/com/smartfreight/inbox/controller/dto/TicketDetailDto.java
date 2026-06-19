package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantDocument;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full ticket view for the detail API (Phase 3.2): the metadata, the email/attachment text, the
 * editable draft, and — for DOCUMENT_REQUEST tickets — the attached document plus the documents on
 * file for this shipment that the operator can attach. Bytes are never included (see
 * {@link DocumentRefDto}).
 */
public record TicketDetailDto(
        Long id,
        String messageId,
        String senderEmail,
        String subject,
        String body,
        String attachmentText,
        String ticketType,
        String extractedIdentifier,
        String status,
        Double aiConfidence,
        LocalDateTime slaDeadlineAt,
        boolean slaBreached,
        boolean proactive,
        String draftReply,
        String aiOriginalDraft,
        String requestedDocType,
        DocumentRefDto attachedDocument,
        List<DocumentRefDto> availableDocuments,
        String assigneeUserId,
        String assigneeEmail,
        Integer retryCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TicketDetailDto from(EmailTicket t,
                                       TenantDocument attached,
                                       List<TenantDocument> available,
                                       String assigneeEmail) {
        return new TicketDetailDto(
                t.getId(),
                t.getMessageId(),
                t.getSenderEmail(),
                t.getSubject(),
                t.getBody(),
                t.getAttachmentText(),
                t.getTicketType() == null ? null : t.getTicketType().name(),
                t.getExtractedIdentifier(),
                t.getStatus() == null ? null : t.getStatus().name(),
                t.getAiConfidence(),
                t.getSlaDeadlineAt(),
                t.isSlaBreached(),
                t.isProactive(),
                t.getDraftReply(),
                t.getAiOriginalDraft(),
                t.getRequestedDocType() == null ? null : t.getRequestedDocType().name(),
                attached == null ? null : DocumentRefDto.from(attached),
                available == null ? List.of() : available.stream().map(DocumentRefDto::from).toList(),
                t.getAssigneeUserId(),
                assigneeEmail,
                t.getRetryCount(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }
}
