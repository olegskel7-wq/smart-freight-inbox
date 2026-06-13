package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.service.AnalyticsService;
import com.smartfreight.inbox.service.InboxQueryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Inbox analytics dashboard (Phase 2.1): KPI cards over the ticket and audit tables, plus the
 * Phase 2.8 natural-language "Ask your inbox" box.
 *
 * Read-only — every number is computed by {@link AnalyticsService} on request. When
 * {@code feature.analytics} is off the page renders a notice instead of the cards
 * (same pattern as the rate-sheets page badge), it never 404s.
 */
@Controller
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final InboxQueryService inboxQueryService;

    public AnalyticsController(AnalyticsService analyticsService, InboxQueryService inboxQueryService) {
        this.analyticsService = analyticsService;
        this.inboxQueryService = inboxQueryService;
    }

    /**
     * GET /analytics — KPI summary for a trailing period.
     *
     * @param days reporting period preset (7/30/90); anything else falls back to 30
     */
    @GetMapping("/analytics")
    public String analytics(@RequestParam(name = "days", defaultValue = "30") int days, Model model) {
        populateDashboard(model, days);
        return "analytics";
    }

    /**
     * POST /analytics/ask — answers a free-text question about the inbox (Phase 2.8) and
     * re-renders the dashboard with the answer. The period is preserved so the page the
     * operator was reading stays put.
     *
     * @param question the natural-language question
     * @param days     the dashboard period to keep showing
     */
    @PostMapping("/analytics/ask")
    public String ask(@RequestParam(name = "q", defaultValue = "") String question,
                      @RequestParam(name = "days", defaultValue = "30") int days,
                      Model model) {
        populateDashboard(model, days);
        if (inboxQueryService.isEnabled() && !question.isBlank()) {
            model.addAttribute("queryAnswer", inboxQueryService.ask(question));
        }
        model.addAttribute("question", question);
        return "analytics";
    }

    /** Shared model setup for both the GET render and the POST ask re-render. */
    private void populateDashboard(Model model, int days) {
        int safeDays = AnalyticsService.sanitizePeriod(days);
        boolean enabled = analyticsService.isEnabled();

        model.addAttribute("featureEnabled", enabled);
        model.addAttribute("queryEnabled", inboxQueryService.isEnabled());
        model.addAttribute("days", safeDays);
        model.addAttribute("allowedPeriods", AnalyticsService.ALLOWED_PERIOD_DAYS);
        if (enabled) {
            model.addAttribute("summary", analyticsService.summarise(safeDays));
        }
    }
}
