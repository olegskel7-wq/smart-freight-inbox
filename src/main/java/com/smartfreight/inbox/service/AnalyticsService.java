package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TicketAuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Inbox analytics aggregation (Phase 2.1) — the numbers behind the {@code /analytics} dashboard.
 *
 * Unlike the Prometheus/Grafana stack (operational metrics for whoever runs the system), this
 * is the customer-facing view: "what did the inbox do for me this month". Everything is computed
 * from PostgreSQL on request — no extra state, no schedulers; ticket volumes per tenant are far
 * below the level where pre-aggregation would pay off.
 *
 * Period attribution: ticket KPIs count tickets CREATED in the period; reply KPIs count audit
 * SEND/AUTO_SEND events RECORDED in the period. A reply sent today on last week's ticket is
 * this period's work — that matches how an operator reads "replies sent".
 */
@Service
public class AnalyticsService {

    /** Feature flag (default ON), see SettingsService.isFeatureEnabled. Plan-gating seam for Phase 2 multi-tenancy. */
    public static final String FEATURE_KEY = "feature.analytics";

    /** Selectable reporting periods, days. Anything else collapses to {@link #DEFAULT_PERIOD_DAYS}. */
    public static final List<Integer> ALLOWED_PERIOD_DAYS = List.of(7, 30, 90);
    public static final int DEFAULT_PERIOD_DAYS = 30;

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

        long manualSends = auditLogRepository.countByActionAndCreatedAtGreaterThanEqual(
                TicketAuditService.ACTION_SEND, since);
        long autoSends = auditLogRepository.countByActionAndCreatedAtGreaterThanEqual(
                TicketAuditService.ACTION_AUTO_SEND, since);
        long slaBreached = emailTicketRepository.countByCreatedAtGreaterThanEqualAndSlaBreachedTrue(since);

        return new AnalyticsSummary(days, byStatus, manualSends, autoSends, slaBreached);
    }

    /**
     * KPI bundle for one reporting period. Percentages are derived here (not in the template)
     * so the divide-by-zero rule — an empty period reads as 0%, not NaN — is unit-testable.
     */
    public record AnalyticsSummary(int periodDays,
                                   Map<TicketStatus, Long> byStatus,
                                   long manualSends,
                                   long autoSends,
                                   long slaBreached) {

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

        private static double pct(long part, long whole) {
            return whole == 0 ? 0.0 : 100.0 * part / whole;
        }
    }
}
