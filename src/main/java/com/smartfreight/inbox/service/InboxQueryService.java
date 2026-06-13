package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.service.AnalyticsService.AnalyticsSummary;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Ask your inbox" — answers free-text questions about the inbox in natural language (Phase 2.8).
 *
 * <p>This is the metric-router design (decision: not text-to-SQL). A question is answered in two
 * cheap LLM calls around a deterministic core:
 * <ol>
 *   <li><b>Route</b> — the extraction model maps the question to one {@link InboxMetric} from a
 *       fixed catalogue (+ a period and an optional ticket type). It never sees the database.</li>
 *   <li><b>Compute</b> — {@link AnalyticsService} produces the real figures (the same Phase 2.1
 *       aggregation, RLS-scoped to the current tenant). The headline sentence built here is already
 *       a correct, complete answer.</li>
 *   <li><b>Phrase</b> — the drafting model rewrites those exact figures into a conversational reply.
 *       If the model is unavailable the deterministic headline is returned verbatim — the user still
 *       gets the right number, never a wrong one.</li>
 * </ol>
 *
 * <p>Both LLM steps run on the current tenant's Ollama endpoint via {@link OllamaClientProvider}.
 * Unlike the email pipeline, this is an interactive read-only query: an open circuit breaker or a
 * failed call degrades gracefully (deterministic headline / a friendly notice), it never throws.
 */
@Service
public class InboxQueryService {

    private static final Logger log = LoggerFactory.getLogger(InboxQueryService.class);

    /** Feature flag (default ON) — read-only analytics, but it does spend LLM time. */
    public static final String FEATURE_KEY = "feature.inbox-query";

    /** Routing prompt template ({@code {catalogue}}, {@code {question}}). Seeded by migration V25. */
    public static final String KEY_QUERY_PROMPT = "ai.inbox-query-prompt";

    /** Phrasing prompt template ({@code {question}}, {@code {facts}}). Seeded by migration V25. */
    public static final String KEY_ANSWER_PROMPT = "ai.inbox-answer-prompt";

    private static final Duration OLLAMA_TIMEOUT = Duration.ofSeconds(60);

    /** Pulls the period (7/30/90) out of the routing reply; anything else falls back to the default. */
    private static final Pattern PERIOD = Pattern.compile("\\b(7|30|90)\\b");

    /** What the user is told when their question doesn't match the catalogue. */
    private static final String GUIDANCE = """
            I can answer questions about your inbox activity — for example:
            • How many RFQs did we get in the last 30 days?
            • What's our average response time this week?
            • What share of replies were sent automatically?
            • How many emails breached their SLA?
            • Where is the inbox getting stuck?""";

    /** Fallback routing prompt when the DB-backed {@code ai.inbox-query-prompt} row is missing. Kept in sync with V25. */
    private static final String DEFAULT_QUERY_PROMPT = """
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
    private static final String DEFAULT_ANSWER_PROMPT = """
            You are an analytics assistant for a UK freight email automation platform.
            Answer the user's question in one or two short, friendly sentences, UK business English.
            Use ONLY the figures provided — never invent or estimate numbers. If a figure is zero, say so plainly.

            Question: {question}

            Figures:
            {facts}

            Answer:""";

    private final AnalyticsService analyticsService;
    private final SettingsService settingsService;
    private final OllamaClientProvider ollamaClients;
    private final MeterRegistry meterRegistry;
    private final String routingModel;
    private final String phrasingModel;

    public InboxQueryService(AnalyticsService analyticsService,
                             SettingsService settingsService,
                             OllamaClientProvider ollamaClients,
                             MeterRegistry meterRegistry,
                             @Value("${ollama.extraction-model:qwen2.5:7b}") String routingModel,
                             @Value("${ollama.drafting-model:llama3.1:8b}") String phrasingModel) {
        this.analyticsService = analyticsService;
        this.settingsService = settingsService;
        this.ollamaClients = ollamaClients;
        this.meterRegistry = meterRegistry;
        this.routingModel = routingModel;
        this.phrasingModel = phrasingModel;
    }

    public boolean isEnabled() {
        return settingsService.isFeatureEnabled(FEATURE_KEY, true);
    }

    /**
     * Answers a free-text question about the inbox.
     *
     * @param question the user's natural-language question
     * @return the answer plus the routing metadata (for display / debugging)
     */
    @Transactional(readOnly = true)
    public InboxAnswer ask(String question) {
        if (question == null || question.isBlank()) {
            return new InboxAnswer(question, GUIDANCE, InboxMetric.UNKNOWN, AnalyticsService.DEFAULT_PERIOD_DAYS);
        }

        QueryIntent intent = route(question);
        meterRegistry.counter("smartfreight_inbox_queries_total", "metric", intent.metric().name()).increment();

        if (intent.metric() == InboxMetric.UNKNOWN) {
            return new InboxAnswer(question, GUIDANCE, InboxMetric.UNKNOWN, intent.periodDays());
        }

        MetricResult result = resolve(intent);
        String answer = phrase(question, result.facts()).orElse(result.headline());
        return new InboxAnswer(question, answer, intent.metric(), intent.periodDays());
    }

    /** LLM step 1: question → structured intent. Tolerant parse; LLM failure → UNKNOWN (guidance). */
    private QueryIntent route(String question) {
        String prompt = settingsService.getString(KEY_QUERY_PROMPT, DEFAULT_QUERY_PROMPT)
                .replace("{catalogue}", InboxMetric.catalogue())
                .replace("{question}", question.strip());

        return callOllama(routingModel, prompt)
                .map(InboxQueryService::parseIntent)
                .orElseGet(() -> new QueryIntent(InboxMetric.UNKNOWN, AnalyticsService.DEFAULT_PERIOD_DAYS, null));
    }

    /**
     * Parses the routing reply. Visible for testing — robust to small-model formatting drift:
     * the metric is the first catalogue name mentioned, the period the first 7/30/90 token, the
     * type the first {@link TicketType} name present (only meaningful for TYPE_COUNT).
     */
    static QueryIntent parseIntent(String raw) {
        InboxMetric metric = InboxMetric.firstMentioned(raw);

        int period = AnalyticsService.DEFAULT_PERIOD_DAYS;
        Matcher m = PERIOD.matcher(raw);
        if (m.find()) {
            period = AnalyticsService.sanitizePeriod(Integer.parseInt(m.group(1)));
        }

        TicketType type = null;
        if (metric == InboxMetric.TYPE_COUNT) {
            String upper = raw.toUpperCase(Locale.ROOT);
            for (TicketType t : TicketType.values()) {
                if (upper.contains(t.name())) {
                    type = t;
                    break;
                }
            }
            // A type count with no type degrades to the full per-type breakdown rather than guessing.
            if (type == null) {
                metric = InboxMetric.TYPE_BREAKDOWN;
            }
        }
        return new QueryIntent(metric, period, type);
    }

    /** Deterministic core: turn the intent into a correct headline sentence + a figures block. */
    private MetricResult resolve(QueryIntent intent) {
        AnalyticsSummary s = analyticsService.summarise(intent.periodDays());
        int p = intent.periodDays();
        String win = "the last " + p + " days";

        return switch (intent.metric()) {
            case EMAILS_RECEIVED -> result(
                    "You received %d emails in %s (%d relevant, %d junk).".formatted(
                            s.totalReceived(), win, s.relevant(), s.irrelevant()),
                    "Emails received: %d%nRelevant: %d%nJunk: %d".formatted(
                            s.totalReceived(), s.relevant(), s.irrelevant()));

            case RELEVANT_COUNT -> result(
                    "You received %d relevant business emails in %s (junk excluded).".formatted(s.relevant(), win),
                    "Relevant emails: %d%nTotal received: %d".formatted(s.relevant(), s.totalReceived()));

            case JUNK_COUNT -> result(
                    "%d junk emails were filtered out in %s (%.1f%% of all mail).".formatted(
                            s.irrelevant(), win, s.irrelevantPct()),
                    "Junk emails: %d%nTotal received: %d".formatted(s.irrelevant(), s.totalReceived()));

            case TYPE_COUNT -> {
                long n = s.byType().getOrDefault(intent.type(), 0L);
                yield result(
                        "You received %d %s emails in %s.".formatted(n, intent.type().name(), win),
                        "%s emails: %d%nRelevant emails: %d".formatted(intent.type().name(), n, s.relevant()));
            }

            case TYPE_BREAKDOWN -> {
                StringBuilder facts = new StringBuilder();
                s.byType().forEach((t, n) -> facts.append(t.name()).append(": ").append(n).append('\n'));
                if (s.untyped() > 0) {
                    facts.append("Unclassified: ").append(s.untyped()).append('\n');
                }
                yield result(
                        "Relevant emails by type over %s — %s.".formatted(win, inline(facts.toString())),
                        facts.toString().strip());
            }

            case STATUS_BREAKDOWN -> {
                StringBuilder facts = new StringBuilder();
                s.byStatus().forEach((st, n) -> facts.append(st.name()).append(": ").append(n).append('\n'));
                yield result(
                        "Tickets by status over %s — %s.".formatted(win, inline(facts.toString())),
                        facts.toString().strip());
            }

            case REPLIES_SENT -> result(
                    "You sent %d replies in %s (%d by an operator, %d automatically).".formatted(
                            s.totalSends(), win, s.manualSends(), s.autoSends()),
                    "Replies sent: %d%nBy operator: %d%nAuto-sent: %d".formatted(
                            s.totalSends(), s.manualSends(), s.autoSends()));

            case AUTOMATION_RATE -> result(
                    "%.1f%% of replies were sent automatically in %s (%d of %d).".formatted(
                            s.autoSendPct(), win, s.autoSends(), s.totalSends()),
                    "Automation rate: %.1f%%%nAuto-sent: %d%nTotal replies: %d".formatted(
                            s.autoSendPct(), s.autoSends(), s.totalSends()));

            case AVG_RESPONSE_TIME -> result(
                    "Your average first-response time over %s was %s.".formatted(win, s.avgResponseFormatted()),
                    "Average first-response time: %s%nReplies sent: %d".formatted(
                            s.avgResponseFormatted(), s.totalSends()));

            case SLA_BREACHES -> result(
                    "%d tickets breached their SLA in %s (%.1f%% of relevant tickets).".formatted(
                            s.slaBreached(), win, s.slaBreachedPct()),
                    "SLA breaches: %d%nRelevant tickets: %d%nBreach rate: %.1f%%".formatted(
                            s.slaBreached(), s.relevant(), s.slaBreachedPct()));

            case BOTTLENECKS -> {
                TicketStatus busiest = busiestOpenStage(s);
                long backlog = busiest == null ? 0L : s.byStatus().getOrDefault(busiest, 0L);
                String facts = ("Backlog by open stage (newest work first):%n"
                        + "  RECEIVED: %d%n  NEW: %d%n  PROCESSING: %d%n  DRAFT_READY (awaiting operator): %d%n"
                        + "  RETRY_LATER: %d%n  FAILED: %d%n"
                        + "SLA breaches: %d (%.1f%% of relevant)%nAverage first-response time: %s").formatted(
                        s.byStatus().getOrDefault(TicketStatus.RECEIVED, 0L),
                        s.byStatus().getOrDefault(TicketStatus.NEW, 0L),
                        s.byStatus().getOrDefault(TicketStatus.PROCESSING, 0L),
                        s.byStatus().getOrDefault(TicketStatus.DRAFT_READY, 0L),
                        s.byStatus().getOrDefault(TicketStatus.RETRY_LATER, 0L),
                        s.byStatus().getOrDefault(TicketStatus.FAILED, 0L),
                        s.slaBreached(), s.slaBreachedPct(), s.avgResponseFormatted());
                String headline = busiest == null || backlog == 0
                        ? "Over %s nothing is backed up — no open tickets are piling up at any stage.".formatted(win)
                        : "Over %s the biggest backlog is %d ticket(s) at the %s stage; %d SLA breaches.".formatted(
                                win, backlog, busiest.name(), s.slaBreached());
                yield result(headline, facts);
            }

            case UNKNOWN -> result(GUIDANCE, GUIDANCE);
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

    /** LLM step 3: phrase the figures conversationally. Empty when the model gives nothing usable. */
    private Optional<String> phrase(String question, String facts) {
        String prompt = settingsService.getString(KEY_ANSWER_PROMPT, DEFAULT_ANSWER_PROMPT)
                .replace("{question}", question.strip())
                .replace("{facts}", facts);
        return callOllama(phrasingModel, prompt);
    }

    /**
     * One Ollama generate call for the CURRENT tenant's endpoint, returning the response text.
     *
     * <p>Unlike {@link AiDraftingService}, this is interactive and read-only: an open breaker or any
     * failure yields {@link Optional#empty()} (the caller degrades), it is never propagated.
     */
    private Optional<String> callOllama(String model, String prompt) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);

            WebClient client = ollamaClients.client();
            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaClients.circuitBreaker().executeSupplier(() -> client
                    .post()
                    .uri("/api/generate")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(OLLAMA_TIMEOUT));

            if (response == null) {
                return Optional.empty();
            }
            String text = String.valueOf(response.getOrDefault("response", "")).trim();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);

        } catch (CallNotPermittedException e) {
            log.warn("Ollama circuit breaker open — inbox query degraded to deterministic answer");
            return Optional.empty();
        } catch (Exception e) {
            log.error("Inbox query Ollama call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static MetricResult result(String headline, String facts) {
        return new MetricResult(headline, facts);
    }

    /** Flattens a multi-line "k: v" block to a single inline clause for the headline sentence. */
    private static String inline(String block) {
        return block.strip().replace("\n", ", ");
    }

    /** Routed question: which metric, over what window, optionally for one type. */
    record QueryIntent(InboxMetric metric, int periodDays, TicketType type) {
    }

    /** Deterministic result of a metric: a complete headline answer + the figures fed to phrasing. */
    private record MetricResult(String headline, String facts) {
    }

    /**
     * The answer shown to the user. {@code metric}/{@code periodDays} are surfaced as a caption
     * ("based on … over N days") so the operator can see how the question was interpreted.
     */
    public record InboxAnswer(String question, String answer, InboxMetric metric, int periodDays) {

        public boolean matched() {
            return metric != InboxMetric.UNKNOWN;
        }
    }
}
