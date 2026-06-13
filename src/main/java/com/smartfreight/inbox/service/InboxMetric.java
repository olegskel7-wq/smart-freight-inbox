package com.smartfreight.inbox.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The fixed catalogue of questions the natural-language inbox assistant can answer (Phase 2.8).
 *
 * <p>2.8 deliberately does NOT let the LLM write SQL. A local 7B/8B model is unreliable at SQL and
 * the product promise is correct, auditable numbers in front of a customer. Instead the model only
 * <em>routes</em> a free-text question to one of these metrics; every figure is then computed
 * deterministically by {@link AnalyticsService} (reusing the Phase 2.1 aggregation) and the model
 * is asked to phrase an answer from those exact numbers. The catalogue is the trust boundary:
 * nothing outside it can be asked, so RLS-scoped reads and "no invented data" hold by construction.
 *
 * <p>{@link #description} is fed to the routing prompt as the menu the model picks from; the enum
 * NAME is the token it must echo back, so the prompt's catalogue can never drift from the code.
 */
public enum InboxMetric {

    EMAILS_RECEIVED("total number of emails received (relevant and junk combined)"),
    RELEVANT_COUNT("number of relevant / genuine business emails received (junk excluded)"),
    JUNK_COUNT("number of junk / irrelevant emails filtered out"),
    TYPE_COUNT("number of emails of ONE specific type — set TYPE to RFQ, STATUS_UPDATE, "
            + "BOOKING_CONFIRM, INVOICE_QUERY, DOCUMENT_REQUEST or GENERAL"),
    TYPE_BREAKDOWN("how relevant emails split across the business types (RFQ, status, booking, ...)"),
    STATUS_BREAKDOWN("how tickets split across pipeline statuses (received, processing, draft, closed, ...)"),
    REPLIES_SENT("number of replies sent, split between operator-sent and auto-sent"),
    AUTOMATION_RATE("the automation rate — share of replies sent automatically without an operator"),
    AVG_RESPONSE_TIME("the average first-response time, from receiving an email to the first reply"),
    SLA_BREACHES("number of tickets that breached their SLA deadline"),
    BOTTLENECKS("where the inbox is getting stuck — backlog by stage, SLA breaches, response time"),
    /** Routing fallback: the question matched nothing in the catalogue. */
    UNKNOWN("the question could not be matched to a supported metric");

    private final String description;

    InboxMetric(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /**
     * The catalogue rendered for the routing prompt — one {@code NAME — description} line per
     * answerable metric ({@link #UNKNOWN} is the parser's fallback, not an option offered to the model).
     */
    public static String catalogue() {
        return Arrays.stream(values())
                .filter(m -> m != UNKNOWN)
                .map(m -> m.name() + " — " + m.description)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Routing is tolerant of small models: rather than demand strict JSON, we scan the model's reply
     * for the first catalogue NAME it mentions. Longer names are matched first so {@code TYPE_COUNT}
     * is never shadowed by a hypothetical prefix. No match → {@link #UNKNOWN}.
     */
    public static InboxMetric firstMentioned(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        InboxMetric best = UNKNOWN;
        int bestIndex = Integer.MAX_VALUE;
        for (InboxMetric m : values()) {
            if (m == UNKNOWN) {
                continue;
            }
            int idx = upper.indexOf(m.name());
            if (idx >= 0 && (idx < bestIndex
                    || (idx == bestIndex && m.name().length() > best.name().length()))) {
                bestIndex = idx;
                best = m;
            }
        }
        return best;
    }
}
