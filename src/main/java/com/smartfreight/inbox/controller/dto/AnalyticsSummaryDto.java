package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.service.AnalyticsService.AnalyticsSummary;

import java.util.List;

/**
 * Flat JSON shape of the analytics dashboard figures (Phase 3.2, React {@code /analytics}).
 *
 * The {@link AnalyticsSummary} record carries the numbers plus a handful of derived accessors
 * (percentages, the "—"/"&lt;1m" formatting) whose edge rules are unit-tested there. Those are
 * NOT JavaBean getters, so Jackson would not serialise them off the record directly — and the
 * enum-keyed {@code byStatus}/{@code byType} maps are awkward to consume in TypeScript. This DTO
 * pre-computes everything the page needs into flat lists/primitives so the React side does zero
 * business math (same precedent as the Thymeleaf template deriving nothing).
 */
public record AnalyticsSummaryDto(
        int periodDays,
        long totalReceived,
        long relevant,
        long irrelevant,
        double irrelevantPct,
        long manualSends,
        long autoSends,
        long totalSends,
        double autoSendPct,
        String avgResponseFormatted,
        long slaBreached,
        double slaBreachedPct,
        long aiDraftsSent,
        long aiDraftsAccepted,
        long aiDraftsEdited,
        double aiAcceptancePct,
        String aiAcceptanceFormatted,
        List<TypeCount> byType,
        long untyped,
        double untypedPct,
        List<StatusCount> byStatus,
        List<String> volumeLabels,
        List<Long> volumeRelevant,
        List<Long> volumeJunk,
        List<Long> confidenceBins,
        long confidenceCount) {

    /** One row of the per-type breakdown: type name, count, and its share of relevant tickets. */
    public record TypeCount(String type, long count, double pct) {}

    /** One row of the status breakdown: status name, count, and its share of all received. */
    public record StatusCount(String status, long count, double pct) {}

    public static AnalyticsSummaryDto from(AnalyticsSummary s) {
        List<TypeCount> types = s.byType().entrySet().stream()
                .map(e -> new TypeCount(e.getKey().name(), e.getValue(), s.typePct(e.getValue())))
                .toList();

        // Status share is against total received (junk included) — mirrors the Thymeleaf table.
        long total = s.totalReceived();
        List<StatusCount> statuses = s.byStatus().entrySet().stream()
                .map(e -> new StatusCount(e.getKey().name(), e.getValue(),
                        total == 0 ? 0.0 : 100.0 * e.getValue() / total))
                .toList();

        return new AnalyticsSummaryDto(
                s.periodDays(), s.totalReceived(), s.relevant(), s.irrelevant(), s.irrelevantPct(),
                s.manualSends(), s.autoSends(), s.totalSends(), s.autoSendPct(), s.avgResponseFormatted(),
                s.slaBreached(), s.slaBreachedPct(), s.aiDraftsSent(), s.aiDraftsAccepted(),
                s.aiDraftsEdited(), s.aiAcceptancePct(), s.aiAcceptanceFormatted(),
                types, s.untyped(), s.typePct(s.untyped()), statuses,
                s.volumeLabels(), s.volumeRelevantSeries(), s.volumeJunkSeries(),
                s.confidenceBins(), s.confidenceCount());
    }
}
