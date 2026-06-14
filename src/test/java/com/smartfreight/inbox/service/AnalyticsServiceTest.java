package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.AiDraftAcceptance;
import com.smartfreight.inbox.repository.ConfidenceBucket;
import com.smartfreight.inbox.repository.DailyVolume;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.StatusCount;
import com.smartfreight.inbox.repository.TicketAuditLogRepository;
import com.smartfreight.inbox.repository.TypeCount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AnalyticsService (Phase 2.1) — KPI aggregation, the divide-by-zero rules
 * for an empty reporting period, chart series shaping (gap fill, fixed histogram size)
 * and response-time display formatting.
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

    private static DailyVolume dayRow(LocalDate day, long total, long junk) {
        return new DailyVolume() {
            @Override public LocalDate getDay() { return day; }
            @Override public long getTotal() { return total; }
            @Override public long getJunk() { return junk; }
        };
    }

    private static ConfidenceBucket bucketRow(int bucket, long count) {
        return new ConfidenceBucket() {
            @Override public int getBucket() { return bucket; }
            @Override public long getCount() { return count; }
        };
    }

    /** Summary with everything empty except the avg-response seconds — for format tests. */
    private static AnalyticsService.AnalyticsSummary summaryWithAvg(Double seconds) {
        return new AnalyticsService.AnalyticsSummary(
                30, Map.of(), Map.of(), 0, 0, 0, 0, seconds, List.of(), List.of(), 0, 0);
    }

    private static AiDraftAcceptance acceptanceRow(long total, long accepted) {
        return new AiDraftAcceptance() {
            @Override public long getTotal() { return total; }
            @Override public long getAccepted() { return accepted; }
        };
    }

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
    void summarise_typeBreakdown_zeroFilledWithUntypedSeparate() {
        when(emailTicketRepository.countByTypeSince(any(), eq(TicketStatus.IRRELEVANT)))
                .thenReturn(List.of(
                        new TypeCount(TicketType.RFQ, 4L),
                        new TypeCount(null, 3L)));

        AnalyticsService.AnalyticsSummary s = analyticsService.summarise(30);

        // Same zero-fill contract as byStatus, but the null group travels separately.
        assertThat(s.byType()).containsKeys(TicketType.values());
        assertThat(s.byType().get(TicketType.RFQ)).isEqualTo(4L);
        assertThat(s.byType().get(TicketType.GENERAL)).isZero();
        assertThat(s.untyped()).isEqualTo(3L);
    }

    @Test
    void summarise_volumeSeries_fillsMissingDaysWithZeros() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(emailTicketRepository.dailyVolumeSince(any()))
                .thenReturn(List.of(dayRow(yesterday, 3L, 1L)));

        AnalyticsService.AnalyticsSummary s = analyticsService.summarise(7);

        // Continuous axis: cutoff day .. today inclusive = 8 points for a 7-day window.
        assertThat(s.volumeByDay()).hasSize(8);
        AnalyticsService.DayVolume y = s.volumeByDay().get(6);
        assertThat(y.relevant()).isEqualTo(2L);   // total minus junk
        assertThat(y.junk()).isEqualTo(1L);
        assertThat(s.volumeByDay().get(0).relevant()).isZero();
        assertThat(s.volumeLabels()).hasSize(8);
        assertThat(s.volumeRelevantSeries().get(6)).isEqualTo(2L);
        assertThat(s.volumeJunkSeries().get(6)).isEqualTo(1L);
    }

    @Test
    void summarise_confidenceBins_fixedSizeWithZeros() {
        when(emailTicketRepository.confidenceHistogramSince(any()))
                .thenReturn(List.of(bucketRow(0, 1L), bucketRow(9, 2L)));

        AnalyticsService.AnalyticsSummary s = analyticsService.summarise(30);

        assertThat(s.confidenceBins()).hasSize(AnalyticsService.CONFIDENCE_BUCKETS);
        assertThat(s.confidenceBins().get(0)).isEqualTo(1L);
        assertThat(s.confidenceBins().get(5)).isZero();
        assertThat(s.confidenceBins().get(9)).isEqualTo(2L);
        assertThat(s.confidenceCount()).isEqualTo(3L);
    }

    @Test
    void summarise_aiAcceptance_computesShareAndComplement() {
        when(emailTicketRepository.aiDraftAcceptanceSince(any())).thenReturn(acceptanceRow(8L, 6L));

        AnalyticsService.AnalyticsSummary s = analyticsService.summarise(30);

        assertThat(s.aiDraftsSent()).isEqualTo(8L);
        assertThat(s.aiDraftsAccepted()).isEqualTo(6L);
        assertThat(s.aiDraftsEdited()).isEqualTo(2L);
        assertThat(s.aiAcceptancePct()).isEqualTo(75.0);
        assertThat(s.aiAcceptanceFormatted()).isEqualTo("75.0%");
    }

    @Test
    void summarise_aiAcceptance_noSends_readsAsDash() {
        // Real repository returns null when the join yields nothing; service must not NPE.
        when(emailTicketRepository.aiDraftAcceptanceSince(any())).thenReturn(null);

        AnalyticsService.AnalyticsSummary s = analyticsService.summarise(30);

        assertThat(s.aiDraftsSent()).isZero();
        assertThat(s.aiAcceptancePct()).isZero();
        assertThat(s.aiAcceptanceFormatted()).isEqualTo("—");
    }

    @Test
    void summarise_emptyPeriod_percentagesAreZeroNotNaN() {
        when(emailTicketRepository.countByStatusSince(any())).thenReturn(List.of());
        // Explicit: the real repository returns null when nothing was answered
        // (Mockito's default for an unstubbed Double is 0.0, which reads as "<1m").
        when(auditLogRepository.avgFirstResponseSeconds(any())).thenReturn(null);

        AnalyticsService.AnalyticsSummary s = analyticsService.summarise(30);

        assertThat(s.totalReceived()).isZero();
        assertThat(s.irrelevantPct()).isZero();
        assertThat(s.autoSendPct()).isZero();
        assertThat(s.slaBreachedPct()).isZero();
        assertThat(s.typePct(0)).isZero();
        assertThat(s.avgResponseFormatted()).isEqualTo("—");
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
    void avgResponseFormatted_humanisesEveryMagnitude() {
        assertThat(summaryWithAvg(null).avgResponseFormatted()).isEqualTo("—");
        assertThat(summaryWithAvg(20.0).avgResponseFormatted()).isEqualTo("<1m");
        assertThat(summaryWithAvg(45 * 60.0).avgResponseFormatted()).isEqualTo("45m");
        assertThat(summaryWithAvg(2 * 3600 + 14 * 60.0).avgResponseFormatted()).isEqualTo("2h 14m");
        assertThat(summaryWithAvg(27 * 3600.0).avgResponseFormatted()).isEqualTo("1d 3h");
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
