package com.smartfreight.inbox.service;

/**
 * Texts of "Ask your inbox" ({@link InboxQueryService}): the guidance shown for an unmatched
 * question, the two prompt templates and the substitution that fills them.
 *
 * <p>Each {@code DEFAULT_*} template is the fallback used when the tenant's DB-backed prompt row is
 * missing, and is kept in sync with migration V25 that seeds it. Pure string building — no I/O, no
 * Spring.
 */
final class InboxQueryPrompts {

    /** What the user is told when their question doesn't match the catalogue. */
    static final String GUIDANCE = """
            I can answer questions about your inbox activity — for example:
            • How many RFQs did we get in the last 30 days?
            • What's our average response time this week?
            • What share of replies were sent automatically?
            • How many emails breached their SLA?
            • What share of our quotes turned into bookings?
            • Where is the inbox getting stuck?""";

    /** Fallback routing prompt when the DB-backed {@code ai.inbox-query-prompt} row is missing. Kept in sync with V25. */
    static final String DEFAULT_QUERY_PROMPT = """
            You route a user's question about their email inbox to exactly ONE metric from the catalogue below.
            Reply with a single line in the form: METRIC | PERIOD_DAYS | TYPE
            - METRIC must be one of the catalogue names exactly.
            - PERIOD_DAYS is one of 7, 30 or 90 (the reporting window the question implies; default 30).
            - TYPE is required only for TYPE_COUNT (one of RFQ, STATUS_UPDATE, BOOKING_CONFIRM, INVOICE_QUERY, DOCUMENT_REQUEST, GENERAL); otherwise write NONE.
            Do not explain. Output only that one line.

            Catalogue:
            {catalogue}

            Question: {question}""";

    /** Fallback phrasing prompt when the DB-backed {@code ai.inbox-answer-prompt} row is missing. Kept in sync with V25. */
    static final String DEFAULT_ANSWER_PROMPT = """
            You are an analytics assistant for a UK freight email automation platform.
            Answer the user's question in one or two short, friendly sentences, UK business English.
            Use ONLY the figures provided — never invent or estimate numbers. If a figure is zero, say so plainly.

            Question: {question}

            Figures:
            {facts}

            Answer:""";

    private InboxQueryPrompts() {
    }

    /** Fills the routing template: the metric catalogue, then the stripped question. */
    static String routing(String template, String question) {
        return template
                .replace("{catalogue}", InboxMetric.catalogue())
                .replace("{question}", question.strip());
    }

    /** Fills the phrasing template: the stripped question, then the figures block. */
    static String answer(String template, String question, String facts) {
        return template
                .replace("{question}", question.strip())
                .replace("{facts}", facts);
    }
}
