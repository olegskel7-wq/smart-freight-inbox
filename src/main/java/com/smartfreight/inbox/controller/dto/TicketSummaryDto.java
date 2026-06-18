package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.entity.EmailTicket;

import java.time.LocalDateTime;

/**
 * Row view of a ticket for the list API (Phase 3.2) — the fields the {@code /tickets} table shows.
 * Enums are emitted as their names (null when unset) so the SPA renders them directly.
 */
public record TicketSummaryDto(
        Long id,
        String senderEmail,
        String subject,
        String ticketType,
        String extractedIdentifier,
        String status,
        Double aiConfidence,
        LocalDateTime slaDeadlineAt,
        boolean slaBreached,
        boolean proactive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TicketSummaryDto from(EmailTicket t) {
        return new TicketSummaryDto(
                t.getId(),
                t.getSenderEmail(),
                t.getSubject(),
                t.getTicketType() == null ? null : t.getTicketType().name(),
                t.getExtractedIdentifier(),
                t.getStatus() == null ? null : t.getStatus().name(),
                t.getAiConfidence(),
                t.getSlaDeadlineAt(),
                t.isSlaBreached(),
                t.isProactive(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }
}
