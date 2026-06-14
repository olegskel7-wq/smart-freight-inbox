package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.AiDraftAcceptance;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TicketAuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Inbox analytics aggregation (Phase 2.1) — the numbers behind the {@code /analytics} dashboard.
 *
 * Unlike the Prometheus/Grafana stack (operational metrics for whoever runs the system), this
 * is the customer-facing view: "what did the inbox do for me this month". Everything is computed
 * from PostgreSQL on request — no extra state, no schedulers; ticket volumes per tenant are far
 * below the level where pre-aggregation would pay off.
 *
 * Period attribution: ticket KPIs count tickets CREATED in the period; reply KPIs (including
 * average first-response time) count audit SEND/AUTO_SEND events RECORDED in the period. A reply
 * sent today on last week's ticket is this period's work — that matches how an operator reads
 * "replies sent".
 */
@Service
public class AnalyticsService {

    /** Feature flag (default ON), see SettingsService.isFeatureEnabled. Plan-gating seam for Phase 2 multi-tenancy. */
    public static final String FEATURE_KEY = "feature.analytics";

    /** Selectable reporting periods, days. Anything else collapses to {@link #DEFAULT_PERIOD_DAYS}. */
    public static final List<Integer> ALLOWED_PERIOD_DAYS = List.of(7, 30, 90);
    public static final int DEFAULT_PERIOD_DAYS = 30;

    /** Confidence histogram resolution: bucket i covers [i/10, (i+1)/10), 1.0 folds into the top bucket. */
    public static final int CONFIDENCE_BUCKETS = 10;

    /** Chart axis labels, e.g. "9 Jun". English always — UK product; never the JVM default locale. */
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final EmailTicketRepository emailTicketRepository;
    private final TicketAuditLogRepository auditLogRepository;
    private final SettingsService settingsService;

    public AnalyticsService(EmailTicketRepository emailTicketRepository,
                            TicketAuditLogRepository auditLogRepository,
                            SettingsService settingsService) {
        this.emailTicketRepository = emailTicketRepository;
        this.auditLogRepository = auditLogRepository;
        this.settingsService = settingsService;
    }

    public boolean isEnabled() {
        return settingsService.isFeatureEnabled(FEATURE_KEY, true);
    }

    /** Clamps a user-supplied period to the allowed presets. */
    public static int sanitizePeriod(int days) {
        return ALLOWED_PERIOD_DAYS.contains(days) ? days : DEFAULT_PERIOD_DAYS;
    }

    /**
     * Computes the KPI summary for the trailing period.
     *
     * @param days reporting period length, must be one of {@link #ALLOWED_PERIOD_DAYS}
     * @return aggregated counts for the period
     */
    @Transactional(readOnly = true)
    public AnalyticsSummary summarise(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        // EnumMap pre-filled with zeros: the template iterates statuses in enum (pipeline) order
        // and shows explicit 0s instead of skipping statuses absent from the period.
        Map<TicketStatus, Long> byStatus = new EnumMap<>(TicketStatus.class);
        for (TicketStatus status : TicketStatus.values()) {
            byStatus.put(status, 0L);
        }
        emailTicketRepository.countByStatusSince(since)
                .forEach(row -> byStatus.put(row.status(), row.count()));

        // Same zero-fill for the per-type breakdown. EnumMap cannot hold the null key, so
        // tickets without a classification verdict are carried separately as "untyped".
        Map<TicketType, Long> byType = new EnumMap<>(TicketType.class);
        for (TicketType type : TicketType.values()) {
            byType.put(type, 0L);
        }
        long[] untyped = {0L};
        emailTicketRepository.countByTypeSince(since, TicketStatus.IRRELEVANT)
                .forEach(row -> {
                    if (row.type() == null) {
                        untyped[0] = row.count();
                    } else {
                        byType.put(row.type(), row.count());
                    }
                });

        long manualSends = auditLogRepository.countByActionAndCreatedAtGreaterThanEqual(
                TicketAuditService.ACTION_SEND, since);
        long autoSends = auditLogRepository.countByActionAndCreatedAtGreaterThanEqual(
                TicketAuditService.ACTION_AUTO_SEND, since);
        long slaBreached = emailTicketRepository.countByCreatedAtGreaterThanEqualAndSlaBreachedTrue(since);
        Double avgResponseSeconds = auditLogRepository.avgFirstResponseSeconds(since);

        AiDraftAcceptance acceptance = emailTicketRepository.aiDraftAcceptanceSince(since);
        long aiDraftsSent = acceptance == null ? 0L : acceptance.getTotal();
        long aiDraftsAccepted = acceptance == null ? 0L : acceptance.getAccepted();

        return new AnalyticsSummary(days, byStatus, byType, untyped[0], manualSends, autoSends,
                slaBreached, avgResponseSeconds, volumeByDay(since), confidenceBins(since),
                aiDraftsSent, aiDraftsAccepted);
    }

    /**
     * Per-day intake series with gaps filled: the SQL only returns days that received mail,
     * but the chart axis must be continuous from the cutoff through today.
     */
    private List<DayVolume> volumeByDay(LocalDateTime since) {
        Map<LocalDate, DayVolume> present = new java.util.HashMap<>();
        emailTicketRepository.dailyVolumeSince(since).forEach(row ->
                present.put(row.getDay(), new DayVolume(
                        DAY_LABEL.format(row.getDay()), row.getTotal() - row.getJunk(), row.getJunk())));

        List<DayVolume> series = new ArrayList<>();
        for (LocalDate d = since.toLocalDate(); !d.isAfter(LocalDate.now()); d = d.plusDays(1)) {
            series.add(present.getOrDefault(d, new DayVolume(DAY_LABEL.format(d), 0L, 0L)));
        }
        return series;
    }

    /** Histogram counts as a fixed-size list, empty buckets as zeros (the SQL omits them). */
    private List<Long> confidenceBins(LocalDateTime since) {
        long[] bins = new long[CONFIDENCE_BUCKETS];
        emailTicketRepository.confidenceHistogramSince(since)
                .forEach(row -> bins[row.getBucket()] = row.getCount());
        return Arrays.stream(bins).boxed().toList();
    }

    /** One point of the volume chart: a day label plus its relevant/junk split. */
    public record DayVolume(String label, long relevant, long junk) {
    }

    /**
     * KPI bundle for one reporting period. Percentages and display formatting are derived here
     * (not in the template) so the edge rules — an empty period reads as 0% not NaN, an
     * unanswered period reads as a dash — are unit-testable.
     */
    public record AnalyticsSummary(int periodDays,
                                   Map<TicketStatus, Long> byStatus,
                                   Map<TicketType, Long> byType,
                                   long untyped,
                                   long manualSends,
                                   long autoSends,
                                   long slaBreached,
                                   Double avgResponseSeconds,
                                   List<DayVolume> volumeByDay,
                                   List<Long> confidenceBins,
                                   long aiDraftsSent,
                                   long aiDraftsAccepted) {

        /** Every email persisted in the period, junk included. */
        public long totalReceived() {
            return byStatus.values().stream().mapToLong(Long::longValue).sum();
        }

        public long irrelevant() {
            return byStatus.getOrDefault(TicketStatus.IRRELEVANT, 0L);
        }

        /** Tickets that entered (or will enter) the AI pipeline — everything the classifier kept. */
        public long relevant() {
            return totalReceived() - irrelevant();
        }

        public long totalSends() {
            return manualSends + autoSends;
        }

        /*
         * Chart series as flat lists of strings/numbers: Thymeleaf's built-in JavaScript
         * serializer (no Jackson 2 on a Boot 4 classpath) only handles simple types,
         * collections and maps — never inline a record/POJO into th:inline="javascript".
         */

        public List<String> volumeLabels() {
            return volumeByDay.stream().map(DayVolume::label).toList();
        }

        public List<Long> volumeRelevantSeries() {
            return volumeByDay.stream().map(DayVolume::relevant).toList();
        }

        public List<Long> volumeJunkSeries() {
            return volumeByDay.stream().map(DayVolume::junk).toList();
        }

        /** Drafts in the period carrying a stored confidence — the histogram population. */
        public long confidenceCount() {
            return confidenceBins.stream().mapToLong(Long::longValue).sum();
        }

        public double irrelevantPct() {
            return pct(irrelevant(), totalReceived());
        }

        /** Share of replies that went out without an operator — the automation rate. */
        public double autoSendPct() {
            return pct(autoSends, totalSends());
        }

        /** Breach share among relevant tickets — IRRELEVANT never carries an SLA clock. */
        public double slaBreachedPct() {
            return pct(slaBreached, relevant());
        }

        /** Replies edited before sending — the complement of acceptance. */
        public long aiDraftsEdited() {
            return aiDraftsSent - aiDraftsAccepted;
        }

        /** Share of AI-drafted replies sent unchanged — the feedback loop's learning signal. */
        public double aiAcceptancePct() {
            return pct(aiDraftsAccepted, aiDraftsSent);
        }

        /** Acceptance as a percentage string, or "—" when no AI-drafted reply was sent in the period. */
        public String aiAcceptanceFormatted() {
            if (aiDraftsSent == 0) {
                return "—";
            }
            return String.format(Locale.ENGLISH, "%.1f%%", aiAcceptancePct());
        }

        /** Share of relevant tickets, for the per-type progress bars. */
        public double typePct(long count) {
            return pct(count, relevant());
        }

        /**
         * Average first-response time as the operator reads it: "—" when nothing was answered,
         * "&lt;1m" under a minute, then "41m" / "2h 14m" / "1d 3h".
         */
        public String avgResponseFormatted() {
            if (avgResponseSeconds == null) {
                return "—";
            }
            long minutes = Math.round(avgResponseSeconds / 60.0);
            if (minutes < 1) {
                return "<1m";
            }
            if (minutes < 60) {
                return minutes + "m";
            }
            long hours = minutes / 60;
            if (hours < 24) {
                return hours + "h " + (minutes % 60) + "m";
            }
            return (hours / 24) + "d " + (hours % 24) + "h";
        }

        private static double pct(long part, long whole) {
            return whole == 0 ? 0.0 : 100.0 * part / whole;
        }
    }
}
