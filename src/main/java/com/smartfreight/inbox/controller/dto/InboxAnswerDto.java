package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.service.InboxQueryService.InboxAnswer;

/**
 * POST {@code /api/analytics/ask} payload — the "Ask your inbox" answer.
 * {@code matched} drives the alert styling (primary when a metric was resolved, secondary for the
 * guidance fallback); {@code metric} is the routed {@code InboxMetric} name for the caption.
 */
public record InboxAnswerDto(
        String question,
        String answer,
        String metric,
        int periodDays,
        boolean matched) {

    public static InboxAnswerDto from(InboxAnswer a) {
        return new InboxAnswerDto(a.question(), a.answer(), a.metric().name(), a.periodDays(), a.matched());
    }
}
