package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.service.InboxMetricResolver.MetricResult;
import com.smartfreight.inbox.service.InboxMetricResolver.QueryIntent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Ask your inbox" — answers free-text questions about the inbox in natural language
 * (see docs/adr/0011-nl-query-metric-router.md).
 *
 * <p>This is the metric-router design (decision: not text-to-SQL). A question is answered in two
 * cheap LLM calls around a deterministic core:
 * <ol>
 *   <li><b>Route</b> — the extraction model maps the question to one {@link InboxMetric} from a
 *       fixed catalogue (+ a period and an optional ticket type). It never sees the database.</li>
 *   <li><b>Compute</b> — {@link InboxMetricResolver} reads the real figures from
 *       {@link AnalyticsService} (the same dashboard aggregation, RLS-scoped to the current tenant).
 *       The headline sentence it builds is already a correct, complete answer.</li>
 *   <li><b>Phrase</b> — the drafting model rewrites those exact figures into a conversational reply.
 *       If the model is unavailable the deterministic headline is returned verbatim — the user still
 *       gets the right number, never a wrong one.</li>
 * </ol>
 *
 * <p>Both LLM steps run on the current tenant's Ollama endpoint via {@link InboxQueryOllamaClient}.
 * Unlike the email pipeline, this is an interactive read-only query: an open circuit breaker or a
 * failed call degrades gracefully (deterministic headline / a friendly notice), it never throws.
 */
@Service
public class InboxQueryService {

    /** Feature flag (default ON) — read-only analytics, but it does spend LLM time. */
    public static final String FEATURE_KEY = "feature.inbox-query";

    /** Routing prompt template ({@code {catalogue}}, {@code {question}}). Seeded by migration V25. */
    public static final String KEY_QUERY_PROMPT = "ai.inbox-query-prompt";

    /** Phrasing prompt template ({@code {question}}, {@code {facts}}). Seeded by migration V25. */
    public static final String KEY_ANSWER_PROMPT = "ai.inbox-answer-prompt";

    /** Pulls the period (7/30/90) out of the routing reply; anything else falls back to the default. */
    private static final Pattern PERIOD = Pattern.compile("\\b(7|30|90)\\b");

    private final InboxMetricResolver metricResolver;
    private final SettingsService settingsService;
    private final InboxQueryOllamaClient ollamaClient;
    private final MeterRegistry meterRegistry;
    private final String routingModel;
    private final String phrasingModel;

    public InboxQueryService(InboxMetricResolver metricResolver,
                             SettingsService settingsService,
                             InboxQueryOllamaClient ollamaClient,
                             MeterRegistry meterRegistry,
                             @Value("${ollama.extraction-model:qwen2.5:7b}") String routingModel,
                             @Value("${ollama.drafting-model:llama3.1:8b}") String phrasingModel) {
        this.metricResolver = metricResolver;
        this.settingsService = settingsService;
        this.ollamaClient = ollamaClient;
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
            return new InboxAnswer(question, InboxQueryPrompts.GUIDANCE, InboxMetric.UNKNOWN,
                    AnalyticsService.DEFAULT_PERIOD_DAYS);
        }

        QueryIntent intent = route(question);
        meterRegistry.counter("smartfreight_inbox_queries_total", "metric", intent.metric().name()).increment();

        if (intent.metric() == InboxMetric.UNKNOWN) {
            return new InboxAnswer(question, InboxQueryPrompts.GUIDANCE, InboxMetric.UNKNOWN, intent.periodDays());
        }

        MetricResult result = metricResolver.resolve(intent);
        String answer = phrase(question, result.facts()).orElse(result.headline());
        return new InboxAnswer(question, answer, intent.metric(), intent.periodDays());
    }

    /** LLM step 1: question → structured intent. Tolerant parse; LLM failure → UNKNOWN (guidance). */
    private QueryIntent route(String question) {
        String prompt = InboxQueryPrompts.routing(
                settingsService.getString(KEY_QUERY_PROMPT, InboxQueryPrompts.DEFAULT_QUERY_PROMPT), question);

        return ollamaClient.generate(routingModel, prompt)
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

    /** LLM step 3: phrase the figures conversationally. Empty when the model gives nothing usable. */
    private Optional<String> phrase(String question, String facts) {
        String prompt = InboxQueryPrompts.answer(
                settingsService.getString(KEY_ANSWER_PROMPT, InboxQueryPrompts.DEFAULT_ANSWER_PROMPT), question, facts);
        return ollamaClient.generate(phrasingModel, prompt);
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
