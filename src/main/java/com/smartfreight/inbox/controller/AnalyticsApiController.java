package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.AnalyticsResponse;
import com.smartfreight.inbox.controller.dto.AnalyticsSummaryDto;
import com.smartfreight.inbox.controller.dto.InboxAnswerDto;
import com.smartfreight.inbox.service.AnalyticsService;
import com.smartfreight.inbox.service.InboxQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * JSON API backing the React {@code /analytics} page (Phase 3.2). It mirrors
 * {@link AnalyticsController} (the Thymeleaf dashboard) but speaks DTOs over {@code /api/analytics}
 * and is secured by the stateless resource-server chain (bearer JWT). The two controllers run side
 * by side during the migration; this one collapses to the only one when Thymeleaf is removed.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsApiController {

    private final AnalyticsService analyticsService;
    private final InboxQueryService inboxQueryService;

    public AnalyticsApiController(AnalyticsService analyticsService, InboxQueryService inboxQueryService) {
        this.analyticsService = analyticsService;
        this.inboxQueryService = inboxQueryService;
    }

    /** Request body for {@code POST /api/analytics/ask}. */
    public record AskRequest(String question) {}

    /**
     * GET /api/analytics — KPI summary for a trailing period (7/30/90, anything else → 30).
     * {@code summary} is null when {@code feature.analytics} is off (no aggregation runs).
     */
    @GetMapping
    public AnalyticsResponse summary(@RequestParam(name = "days", defaultValue = "30") int days) {
        int safeDays = AnalyticsService.sanitizePeriod(days);
        boolean enabled = analyticsService.isEnabled();
        AnalyticsSummaryDto dto = enabled
                ? AnalyticsSummaryDto.from(analyticsService.summarise(safeDays))
                : null;
        return new AnalyticsResponse(
                enabled, inboxQueryService.isEnabled(), safeDays,
                AnalyticsService.ALLOWED_PERIOD_DAYS, dto);
    }

    /**
     * POST /api/analytics/ask — answers a free-text question about the inbox (Phase 2.8). The
     * period lives in the routed answer (the service picks it from the question), so unlike the
     * Thymeleaf form there is no dashboard period to preserve here. 404 when the feature is off,
     * 400 on a blank question.
     */
    @PostMapping("/ask")
    public InboxAnswerDto ask(@RequestBody AskRequest request) {
        if (!inboxQueryService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Inbox query feature is disabled");
        }
        String question = request.question();
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question must not be blank");
        }
        return InboxAnswerDto.from(inboxQueryService.ask(question));
    }
}
