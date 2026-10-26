package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.entity.EmailTicket;

import java.time.LocalDateTime;

/**
 * Row view of a ticket for the list API (Phase 3.2) — the fields the {@code /tickets} table shows.
 * Enums are emitted as their names (null when unset) so the SPA renders them directly.
 *
 * <p>{@code snippet} is a one-line preview of the email body for the mail-style list (Phase 3.2 UI,
 * variant C): whitespace collapsed and capped server-side, like {@link TrainingExampleDto}, so a
 * list page never ships full bodies.
 */
public record TicketSummaryDto(
        Long id,
        String senderEmail,
        String subject,
        String snippet,
        String ticketType,
        String urgency,
        boolean sentimentNegative,
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
    private static final int SNIPPET_MAX = 160;

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
                snippet(t.getBody()),
                t.getTicketType() == null ? null : t.getTicketType().name(),
                t.getUrgency() == null ? null : t.getUrgency().name(),
                t.isSentimentNegative(),
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

    /** Body as one line (whitespace runs collapsed), trimmed to {@code SNIPPET_MAX} with an ellipsis. */
    static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        // Cut before collapsing: bodies can be megabytes and only the first line-worth is shown.
        String head = body.length() > SNIPPET_MAX * 4 ? body.substring(0, SNIPPET_MAX * 4) : body;
        String line = head.strip().replaceAll("\\s+", " ");
        return line.length() <= SNIPPET_MAX ? line : line.substring(0, SNIPPET_MAX - 1) + "…";
    }
}
