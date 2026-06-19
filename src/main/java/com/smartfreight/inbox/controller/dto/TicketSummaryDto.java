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
        String assigneeUserId,
        String assigneeEmail,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /** Convenience overload — no resolved assignee email (used where the email isn't looked up). */
    public static TicketSummaryDto from(EmailTicket t) {
        return from(t, null);
    }

    /**
     * @param assigneeEmail the assignee's email resolved from Keycloak (Phase 3.3 S2), or null when
     *                      the ticket is unassigned or the email could not be resolved.
     */
    public static TicketSummaryDto from(EmailTicket t, String assigneeEmail) {
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
                t.getAssigneeUserId(),
                assigneeEmail,
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }
}
