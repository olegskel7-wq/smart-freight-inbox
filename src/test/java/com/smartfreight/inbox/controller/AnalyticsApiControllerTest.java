package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.service.AnalyticsService;
import com.smartfreight.inbox.service.AnalyticsService.AnalyticsSummary;
import com.smartfreight.inbox.service.AnalyticsService.DayVolume;
import com.smartfreight.inbox.service.InboxMetric;
import com.smartfreight.inbox.service.InboxQueryService;
import com.smartfreight.inbox.service.InboxQueryService.InboxAnswer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for the JSON analytics API (Phase 3.2). No security/tenant filter here
 * (covered by KeycloakJwtRoleConverterTest / TenantFilterTest); this verifies the DTO flattening,
 * period sanitisation, the feature-off path, and the ask endpoint's guards.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsApiControllerTest {

    @Mock private AnalyticsService analyticsService;
    @Mock private InboxQueryService inboxQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(analyticsService.isEnabled()).thenReturn(true);
        when(inboxQueryService.isEnabled()).thenReturn(true);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AnalyticsApiController(analyticsService, inboxQueryService))
                .build();
    }

    private AnalyticsSummary summary(int days) {
        return new AnalyticsSummary(
                days,
                Map.of(TicketStatus.DRAFT_READY, 3L, TicketStatus.IRRELEVANT, 1L),
                Map.of(TicketType.RFQ, 2L),
                1L,                 // untyped
                4L,                 // manualSends
                1L,                 // autoSends
                0L,                 // slaBreached
                null,               // avgResponseSeconds → "—"
                List.of(new DayVolume("9 Jun", 2L, 1L)),
                List.of(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 2L, 1L),
                3L,                 // aiDraftsSent
                3L,                 // aiDraftsAccepted
                5L,                 // quotesSent
                2L);               // quotesConverted
    }

    @Test
    void summaryReturnsFlattenedDto() throws Exception {
        // sanitizePeriod is static; AnalyticsService.summarise is mocked for the chosen period.
        when(analyticsService.summarise(AnalyticsService.sanitizePeriod(30))).thenReturn(summary(30));

        mockMvc.perform(get("/api/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureEnabled").value(true))
                .andExpect(jsonPath("$.queryEnabled").value(true))
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.allowedPeriods").isArray())
                .andExpect(jsonPath("$.summary.totalReceived").value(4))
                .andExpect(jsonPath("$.summary.relevant").value(3))
                .andExpect(jsonPath("$.summary.avgResponseFormatted").value("—"))
                .andExpect(jsonPath("$.summary.aiAcceptanceFormatted").value("100.0%"))
                .andExpect(jsonPath("$.summary.quotesSent").value(5))
                .andExpect(jsonPath("$.summary.quotesConverted").value(2))
                .andExpect(jsonPath("$.summary.quoteConversionFormatted").value("40.0%"))
                .andExpect(jsonPath("$.summary.volumeLabels[0]").value("9 Jun"))
                .andExpect(jsonPath("$.summary.confidenceBins.length()").value(10));
    }

    @Test
    void unknownPeriodFallsBackTo30() throws Exception {
        when(analyticsService.summarise(30)).thenReturn(summary(30));

        mockMvc.perform(get("/api/analytics").param("days", "365"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30));

        verify(analyticsService).summarise(30);
    }

    @Test
    void featureOffReturnsNullSummaryWithoutRunningQueries() throws Exception {
        when(analyticsService.isEnabled()).thenReturn(false);

        mockMvc.perform(get("/api/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureEnabled").value(false))
                .andExpect(jsonPath("$.summary").doesNotExist());

        verify(analyticsService, never()).summarise(anyInt());
    }

    @Test
    void askReturnsRoutedAnswer() throws Exception {
        when(inboxQueryService.ask("how many rfqs"))
                .thenReturn(new InboxAnswer("how many rfqs", "You received 2 RFQs.",
                        InboxMetric.TYPE_COUNT, 30));

        mockMvc.perform(post("/api/analytics/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"how many rfqs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("You received 2 RFQs."))
                .andExpect(jsonPath("$.metric").value("TYPE_COUNT"))
                .andExpect(jsonPath("$.matched").value(true));
    }

    @Test
    void askBlankQuestionIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/analytics/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest());

        verify(inboxQueryService, never()).ask(anyString());
    }

    @Test
    void askWhenFeatureDisabledIsNotFound() throws Exception {
        when(inboxQueryService.isEnabled()).thenReturn(false);

        mockMvc.perform(post("/api/analytics/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"how many rfqs\"}"))
                .andExpect(status().isNotFound());

        verify(inboxQueryService, never()).ask(anyString());
    }
}
