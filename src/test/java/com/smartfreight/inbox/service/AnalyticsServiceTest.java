package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.StatusCount;
import com.smartfreight.inbox.repository.TicketAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AnalyticsService (Phase 2.1) — KPI aggregation and the
 * divide-by-zero rules for an empty reporting period.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private TicketAuditLogRepository auditLogRepository;

    @Mock
    private SettingsService settingsService;

    @InjectMocks
    private AnalyticsService analyticsService;

    @Test
    void summarise_aggregatesCountsAndPercentages() {
        when(emailTicketRepository.countByStatusSince(any())).thenReturn(List.of(
                new StatusCount(TicketStatus.DRAFT_READY, 6L),
                new StatusCount(TicketStatus.AUTO_SENT, 2L),
                new StatusCount(TicketStatus.IRRELEVANT, 2L)));
        when(auditLogRepository.countByActionAndCreatedAtGreaterThanEqual(
                eq(TicketAuditService.ACTION_SEND), any())).thenReturn(3L);
        when(auditLogRepository.countByActionAndCreatedAtGreaterThanEqual(
                eq(TicketAuditService.ACTION_AUTO_SEND), any())).thenReturn(2L);
        when(emailTicketRepository.countByCreatedAtGreaterThanEqualAndSlaBreachedTrue(any())).thenReturn(2L);

        AnalyticsService.AnalyticsSummary s = analyticsService.summarise(30);

        assertThat(s.totalReceived()).isEqualTo(10);
        assertThat(s.irrelevant()).isEqualTo(2);
        assertThat(s.relevant()).isEqualTo(8);
        assertThat(s.totalSends()).isEqualTo(5);
        assertThat(s.irrelevantPct()).isEqualTo(20.0);
        assertThat(s.autoSendPct()).isEqualTo(40.0);
        assertThat(s.slaBreachedPct()).isEqualTo(25.0);   // 2 breaches / 8 relevant
    }

    @Test
    void summarise_fillsAllStatusesWithZeros() {
        when(emailTicketRepository.countByStatusSince(any()))
                .thenReturn(List.of(new StatusCount(TicketStatus.CLOSED, 1L)));

        AnalyticsService.AnalyticsSummary s = analyticsService.summarise(7);

        // The template iterates the full map — every status must be present, absent ones as 0.
        assertThat(s.byStatus()).containsKeys(TicketStatus.values());
        assertThat(s.byStatus().get(TicketStatus.CLOSED)).isEqualTo(1L);
        assertThat(s.byStatus().get(TicketStatus.FAILED)).isZero();
    }

    @Test
    void summarise_emptyPeriod_percentagesAreZeroNotNaN() {
        when(emailTicketRepository.countByStatusSince(any())).thenReturn(List.of());

        AnalyticsService.AnalyticsSummary s = analyticsService.summarise(30);

        assertThat(s.totalReceived()).isZero();
        assertThat(s.irrelevantPct()).isZero();
        assertThat(s.autoSendPct()).isZero();
        assertThat(s.slaBreachedPct()).isZero();
    }

    @Test
    void summarise_cutoffMatchesRequestedPeriod() {
        when(emailTicketRepository.countByStatusSince(any())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now().minusDays(7);
        analyticsService.summarise(7);
        LocalDateTime after = LocalDateTime.now().minusDays(7);

        org.mockito.ArgumentCaptor<LocalDateTime> captor =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(emailTicketRepository).countByStatusSince(captor.capture());
        assertThat(captor.getValue()).isBetween(before, after);
    }

    @Test
    void sanitizePeriod_acceptsPresetsAndRejectsEverythingElse() {
        assertThat(AnalyticsService.sanitizePeriod(7)).isEqualTo(7);
        assertThat(AnalyticsService.sanitizePeriod(30)).isEqualTo(30);
        assertThat(AnalyticsService.sanitizePeriod(90)).isEqualTo(90);
        assertThat(AnalyticsService.sanitizePeriod(365)).isEqualTo(30);
        assertThat(AnalyticsService.sanitizePeriod(-1)).isEqualTo(30);
        assertThat(AnalyticsService.sanitizePeriod(0)).isEqualTo(30);
    }

    @Test
    void isEnabled_delegatesToSettingsDefaultOn() {
        when(settingsService.isFeatureEnabled(AnalyticsService.FEATURE_KEY, true)).thenReturn(true);

        assertThat(analyticsService.isEnabled()).isTrue();
        verify(settingsService).isFeatureEnabled("feature.analytics", true);
    }
}
