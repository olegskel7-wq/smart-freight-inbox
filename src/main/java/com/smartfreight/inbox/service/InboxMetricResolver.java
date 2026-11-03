package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.service.AnalyticsService.AnalyticsSummary;
import com.smartfreight.inbox.service.InboxQueryService.QueryIntent;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The deterministic core of "Ask your inbox": turns a routed {@link QueryIntent} into the real
 * figures from {@link AnalyticsService} (RLS-scoped to the current tenant) — a headline sentence
 * that is already a correct, complete answer, plus the figures block handed to the phrasing model.
 */
@Component
class InboxMetricResolver {

    private final AnalyticsService analyticsService;

    InboxMetricResolver(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /** Turn the intent into a correct headline sentence + a figures block. */
    MetricResult resolve(QueryIntent intent) {
        AnalyticsSummary s = analyticsService.summarise(intent.periodDays());
        int p = intent.periodDays();
        String win = "the last " + p + " days";

        return switch (intent.metric()) {
            case EMAILS_RECEIVED -> result(
                    fmt("You received %d emails in %s (%d relevant, %d junk).",
                            s.totalReceived(), win, s.relevant(), s.irrelevant()),
                    fmt("Emails received: %d\nRelevant: %d\nJunk: %d",
                            s.totalReceived(), s.relevant(), s.irrelevant()));

            case RELEVANT_COUNT -> result(
                    fmt("You received %d relevant business emails in %s (junk excluded).", s.relevant(), win),
                    fmt("Relevant emails: %d\nTotal received: %d", s.relevant(), s.totalReceived()));

            case JUNK_COUNT -> result(
                    fmt("%d junk emails were filtered out in %s (%.1f%% of all mail).",
                            s.irrelevant(), win, s.irrelevantPct()),
                    fmt("Junk emails: %d\nTotal received: %d", s.irrelevant(), s.totalReceived()));

            case TYPE_COUNT -> {
                long n = s.byType().getOrDefault(intent.type(), 0L);
                yield result(
                        fmt("You received %d %s emails in %s.", n, intent.type().name(), win),
                        fmt("%s emails: %d\nRelevant emails: %d", intent.type().name(), n, s.relevant()));
            }

            case TYPE_BREAKDOWN -> {
                StringBuilder facts = new StringBuilder();
                s.byType().forEach((t, n) -> facts.append(t.name()).append(": ").append(n).append('\n'));
                if (s.untyped() > 0) {
                    facts.append("Unclassified: ").append(s.untyped()).append('\n');
                }
                yield result(
                        fmt("Relevant emails by type over %s — %s.", win, inline(facts.toString())),
                        facts.toString().strip());
            }

            case STATUS_BREAKDOWN -> {
                StringBuilder facts = new StringBuilder();
                s.byStatus().forEach((st, n) -> facts.append(st.name()).append(": ").append(n).append('\n'));
                yield result(
                        fmt("Tickets by status over %s — %s.", win, inline(facts.toString())),
                        facts.toString().strip());
            }

            case REPLIES_SENT -> result(
                    fmt("You sent %d replies in %s (%d by an operator, %d automatically).",
                            s.totalSends(), win, s.manualSends(), s.autoSends()),
                    fmt("Replies sent: %d\nBy operator: %d\nAuto-sent: %d",
                            s.totalSends(), s.manualSends(), s.autoSends()));

            case AUTOMATION_RATE -> result(
                    fmt("%.1f%% of replies were sent automatically in %s (%d of %d).",
                            s.autoSendPct(), win, s.autoSends(), s.totalSends()),
                    fmt("Automation rate: %.1f%%\nAuto-sent: %d\nTotal replies: %d",
                            s.autoSendPct(), s.autoSends(), s.totalSends()));

            case AVG_RESPONSE_TIME -> result(
                    fmt("Your average first-response time over %s was %s.", win, s.avgResponseFormatted()),
                    fmt("Average first-response time: %s\nReplies sent: %d",
                            s.avgResponseFormatted(), s.totalSends()));

            case SLA_BREACHES -> result(
                    fmt("%d tickets breached their SLA in %s (%.1f%% of relevant tickets).",
                            s.slaBreached(), win, s.slaBreachedPct()),
                    fmt("SLA breaches: %d\nRelevant tickets: %d\nBreach rate: %.1f%%",
                            s.slaBreached(), s.relevant(), s.slaBreachedPct()));

            case QUOTE_CONVERSION -> result(
                    s.quotesSent() == 0
                            ? fmt("No quotes were sent in %s, so there is no conversion rate yet.", win)
                            : fmt("%s of quotes turned into bookings in %s (%d of %d quotes booked).",
                                    s.quoteConversionFormatted(), win, s.quotesConverted(), s.quotesSent()),
                    fmt("Quote-to-booking conversion: %s\nQuotes sent: %d\nBooked: %d",
                            s.quoteConversionFormatted(), s.quotesSent(), s.quotesConverted()));

            case BOTTLENECKS -> {
                TicketStatus busiest = busiestOpenStage(s);
                long backlog = busiest == null ? 0L : s.byStatus().getOrDefault(busiest, 0L);
                String facts = fmt("Backlog by open stage (newest work first):\n"
                        + "  RECEIVED: %d\n  NEW: %d\n  PROCESSING: %d\n  DRAFT_READY (awaiting operator): %d\n"
                        + "  RETRY_LATER: %d\n  FAILED: %d\n"
                        + "SLA breaches: %d (%.1f%% of relevant)\nAverage first-response time: %s",
                        s.byStatus().getOrDefault(TicketStatus.RECEIVED, 0L),
                        s.byStatus().getOrDefault(TicketStatus.NEW, 0L),
                        s.byStatus().getOrDefault(TicketStatus.PROCESSING, 0L),
                        s.byStatus().getOrDefault(TicketStatus.DRAFT_READY, 0L),
                        s.byStatus().getOrDefault(TicketStatus.RETRY_LATER, 0L),
                        s.byStatus().getOrDefault(TicketStatus.FAILED, 0L),
                        s.slaBreached(), s.slaBreachedPct(), s.avgResponseFormatted());
                String headline = busiest == null || backlog == 0
                        ? fmt("Over %s nothing is backed up — no open tickets are piling up at any stage.", win)
                        : fmt("Over %s the biggest backlog is %d ticket(s) at the %s stage; %d SLA breaches.",
                                win, backlog, busiest.name(), s.slaBreached());
                yield result(headline, facts);
            }

            case UNKNOWN -> result(InboxQueryService.GUIDANCE, InboxQueryService.GUIDANCE);
        };
    }

    /** The non-terminal stage holding the most tickets — where work is actually piling up. */
    private static TicketStatus busiestOpenStage(AnalyticsSummary s) {
        TicketStatus busiest = null;
        long max = -1;
        for (TicketStatus st : new TicketStatus[]{TicketStatus.RECEIVED, TicketStatus.NEW, TicketStatus.PROCESSING,
                TicketStatus.DRAFT_READY, TicketStatus.RETRY_LATER, TicketStatus.FAILED}) {
            long n = s.byStatus().getOrDefault(st, 0L);
            if (n > max) {
                max = n;
                busiest = st;
            }
        }
        return busiest;
    }

    private static MetricResult result(String headline, String facts) {
        return new MetricResult(headline, facts);
    }

    /**
     * Formats with a fixed English locale and {@code \n} line breaks, so headlines and the facts
     * block are identical on every JVM ({@code 20.0%}, never the default-locale {@code 20,0%}).
     */
    private static String fmt(String template, Object... args) {
        return String.format(Locale.ENGLISH, template, args);
    }

    /** Flattens a multi-line "k: v" block to a single inline clause for the headline sentence. */
    private static String inline(String block) {
        return block.strip().replace("\n", ", ");
    }

    /** Deterministic result of a metric: a complete headline answer + the figures fed to phrasing. */
    record MetricResult(String headline, String facts) {
    }
}
