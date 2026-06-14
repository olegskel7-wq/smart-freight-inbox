package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.service.AnalyticsService;
import com.smartfreight.inbox.service.InboxQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Standalone MockMvc tests for the analytics dashboard controller (Phase 2.1):
 * period sanitisation and the feature-flag-off path (no aggregation queries run).
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsControllerTest {

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private InboxQueryService inboxQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Explicit ViewResolver: the view name "analytics" equals the request path /analytics,
        // and the standalone default resolver would reject it as a circular view path.
        InternalResourceViewResolver viewResolver = new InternalResourceViewResolver("/templates/", ".html");
        mockMvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(analyticsService, inboxQueryService))
                .setViewResolvers(viewResolver)
                .build();
    }

    private AnalyticsService.AnalyticsSummary emptySummary(int days) {
        return new AnalyticsService.AnalyticsSummary(
                days, Map.of(), Map.of(), 0, 0, 0, 0, null, java.util.List.of(), java.util.List.of(), 0, 0);
    }

    @Test
    void defaultPeriodIs30Days() throws Exception {
        when(analyticsService.isEnabled()).thenReturn(true);
        when(analyticsService.summarise(30)).thenReturn(emptySummary(30));

        mockMvc.perform(get("/analytics"))
                .andExpect(status().isOk())
                .andExpect(view().name("analytics"))
                .andExpect(model().attribute("days", 30))
                .andExpect(model().attributeExists("summary"));
    }

    @Test
    void unknownPeriodFallsBackTo30() throws Exception {
        when(analyticsService.isEnabled()).thenReturn(true);
        when(analyticsService.summarise(30)).thenReturn(emptySummary(30));

        mockMvc.perform(get("/analytics").param("days", "365"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("days", 30));

        verify(analyticsService).summarise(30);
    }

    @Test
    void presetPeriodIsHonoured() throws Exception {
        when(analyticsService.isEnabled()).thenReturn(true);
        when(analyticsService.summarise(7)).thenReturn(emptySummary(7));

        mockMvc.perform(get("/analytics").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("days", 7));
    }

    @Test
    void featureOff_rendersPageWithoutRunningQueries() throws Exception {
        when(analyticsService.isEnabled()).thenReturn(false);

        mockMvc.perform(get("/analytics"))
                .andExpect(status().isOk())
                .andExpect(view().name("analytics"))
                .andExpect(model().attribute("featureEnabled", false))
                .andExpect(model().attributeDoesNotExist("summary"));

        verify(analyticsService, never()).summarise(anyInt());
    }
}
