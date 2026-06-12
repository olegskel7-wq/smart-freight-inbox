package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.service.AnalyticsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Inbox analytics dashboard (Phase 2.1): KPI cards over the ticket and audit tables.
 *
 * Read-only — every number is computed by {@link AnalyticsService} on request. When
 * {@code feature.analytics} is off the page renders a notice instead of the cards
 * (same pattern as the rate-sheets page badge), it never 404s.
 */
@Controller
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * GET /analytics — KPI summary for a trailing period.
     *
     * @param days reporting period preset (7/30/90); anything else falls back to 30
     */
    @GetMapping("/analytics")
    public String analytics(@RequestParam(name = "days", defaultValue = "30") int days, Model model) {
        int safeDays = AnalyticsService.sanitizePeriod(days);
        boolean enabled = analyticsService.isEnabled();

        model.addAttribute("featureEnabled", enabled);
        model.addAttribute("days", safeDays);
        model.addAttribute("allowedPeriods", AnalyticsService.ALLOWED_PERIOD_DAYS);
        if (enabled) {
            model.addAttribute("summary", analyticsService.summarise(safeDays));
        }
        return "analytics";
    }
}
