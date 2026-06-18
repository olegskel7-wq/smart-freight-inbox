package com.smartfreight.inbox.controller.dto;

import java.util.List;

/**
 * GET {@code /api/analytics} payload (Phase 3.2). Carries the feature flags and the selected
 * period alongside the figures, so the SPA renders the same "feature disabled" notice and the
 * "Ask your inbox" card visibility the Thymeleaf page did. {@code summary} is null when
 * {@code feature.analytics} is off (no aggregation runs).
 */
public record AnalyticsResponse(
        boolean featureEnabled,
        boolean queryEnabled,
        int days,
        List<Integer> allowedPeriods,
        AnalyticsSummaryDto summary) {
}
