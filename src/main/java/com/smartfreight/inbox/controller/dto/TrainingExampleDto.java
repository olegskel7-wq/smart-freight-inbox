package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.entity.TrainingExample;

import java.time.LocalDateTime;

/**
 * One captured operator correction for the React {@code /training-examples} table (Phase 3.2).
 *
 * <p>This is a list page, so — like {@link TicketSummaryDto} — the rows are lean: the three TEXT
 * fields (which hold full email bodies and drafts) are abbreviated server-side to the same widths
 * the Thymeleaf page rendered, instead of shipping the whole blob for a 120-char preview.
 * {@code ticketType} is the enum name (or null → the SPA shows "Unclassified").
 */
public record TrainingExampleDto(
        Long id,
        String ticketType,
        String enquiryPreview,
        String aiDraftPreview,
        String operatorFinalPreview,
        LocalDateTime createdAt
) {
    private static final int ENQUIRY_MAX = 120;
    private static final int DRAFT_MAX = 140;

    public static TrainingExampleDto from(TrainingExample e) {
        return new TrainingExampleDto(
                e.getId(),
                e.getTicketType() == null ? null : e.getTicketType().name(),
                abbreviate(e.getEnquiryText(), ENQUIRY_MAX),
                abbreviate(e.getAiDraft(), DRAFT_MAX),
                abbreviate(e.getOperatorFinal(), DRAFT_MAX),
                e.getCreatedAt()
        );
    }

    /** Trims to at most {@code max} characters, appending an ellipsis when the text was cut. */
    private static String abbreviate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
    }
}
