package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.service.AnalyticsService.AnalyticsSummary;
import com.smartfreight.inbox.service.AnalyticsService.DayVolume;
import com.smartfreight.inbox.service.InboxQueryService.InboxAnswer;
import com.smartfreight.inbox.service.InboxQueryService.QueryIntent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Phase 2.8 inbox query assistant: the tolerant routing parse, the
 * deterministic metric resolution, and graceful degradation when the local LLM is unavailable.
 *
 * The two Ollama calls (route, then phrase) share the same WebClient chain, so the stub returns
 * the routing reply first and the phrasing reply second.
 */
@ExtendWith(MockitoExtension.class)
class InboxQueryServiceTest {

    @Mock private WebClient webClient;
    @Mock private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private WebClient.RequestBodySpec requestBodySpec;
    @Mock private WebClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;
    @Mock private AnalyticsService analyticsService;
    @Mock private SettingsService settingsService;
    @Mock private OllamaClientProvider ollamaClients;

    /** A default locale whose decimal separator is a comma. */
    private static final Locale RU = Locale.forLanguageTag("ru-RU");

    private InboxQueryService service;
    private CircuitBreaker circuitBreaker;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        // Resolve every prompt to the code-level default (arg 1) — un-customised tenant.
        lenient().when(settingsService.getString(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        circuitBreaker = CircuitBreaker.ofDefaults("inbox-query-test");
        lenient().when(ollamaClients.client()).thenReturn(webClient);
        lenient().when(ollamaClients.circuitBreaker()).thenReturn(circuitBreaker);
        meterRegistry = new SimpleMeterRegistry();
        service = new InboxQueryService(new InboxMetricResolver(analyticsService), settingsService, ollamaClients,
                meterRegistry, "qwen2.5:7b", "llama3.1:8b");
    }

    // ---- parseIntent: tolerant of small-model formatting drift ----

    @Test
    void parseIntent_pipeFormat_extractsMetricPeriodType() {
        QueryIntent intent = InboxQueryService.parseIntent("TYPE_COUNT | 30 | RFQ");

        assertThat(intent.metric()).isEqualTo(InboxMetric.TYPE_COUNT);
        assertThat(intent.periodDays()).isEqualTo(30);
        assertThat(intent.type()).isEqualTo(TicketType.RFQ);
    }

    @Test
    void parseIntent_prose_stillFindsMetricAndPeriod() {
        QueryIntent intent = InboxQueryService.parseIntent(
                "The best match is AUTOMATION_RATE over the last 7 days.");

        assertThat(intent.metric()).isEqualTo(InboxMetric.AUTOMATION_RATE);
        assertThat(intent.periodDays()).isEqualTo(7);
        assertThat(intent.type()).isNull();
    }

    @Test
    void parseIntent_typeCountWithoutType_degradesToBreakdown() {
        // A per-type count with no type to count would be a guess — fall back to the full breakdown.
        QueryIntent intent = InboxQueryService.parseIntent("TYPE_COUNT | 30 | NONE");

        assertThat(intent.metric()).isEqualTo(InboxMetric.TYPE_BREAKDOWN);
        assertThat(intent.type()).isNull();
    }

    @Test
    void parseIntent_unmatched_isUnknownWithDefaultPeriod() {
        QueryIntent intent = InboxQueryService.parseIntent("no idea what you mean");

        assertThat(intent.metric()).isEqualTo(InboxMetric.UNKNOWN);
        assertThat(intent.periodDays()).isEqualTo(AnalyticsService.DEFAULT_PERIOD_DAYS);
    }

    // ---- ask: full route → compute → phrase path ----

    @Test
    void ask_typeCount_returnsPhrasedAnswerForTheRightFigure() {
        when(analyticsService.summarise(30)).thenReturn(summaryWithType(TicketType.RFQ, 5));
        stubOllamaSequence(
                Map.of("response", "TYPE_COUNT | 30 | RFQ"),
                Map.of("response", "You received 5 RFQ enquiries in the last 30 days."));

        InboxAnswer answer = service.ask("How many RFQs did we get this month?");

        assertThat(answer.metric()).isEqualTo(InboxMetric.TYPE_COUNT);
        assertThat(answer.periodDays()).isEqualTo(30);
        assertThat(answer.answer()).isEqualTo("You received 5 RFQ enquiries in the last 30 days.");
        assertThat(answer.matched()).isTrue();
        verify(ollamaClients, times(2)).recordUsage(eq("inbox-query"), anyString(), any());
    }

    @Test
    void ask_phrasingUnavailable_fallsBackToDeterministicHeadline() {
        when(analyticsService.summarise(7)).thenReturn(summaryWithType(TicketType.RFQ, 3));
        // Route succeeds; the phrasing call returns nothing usable.
        stubOllamaSequence(
                Map.of("response", "TYPE_COUNT | 7 | RFQ"),
                Map.of("response", ""));

        InboxAnswer answer = service.ask("rfqs last week?");

        // The number is still correct — the headline sentence carries it.
        assertThat(answer.answer()).contains("3").contains("RFQ").contains("7 days");
        assertThat(answer.metric()).isEqualTo(InboxMetric.TYPE_COUNT);
    }

    @Test
    void ask_quoteConversion_headlineCarriesTheRate() {
        when(analyticsService.summarise(30)).thenReturn(summaryWithConversion(10, 4));
        // Route to the new metric; phrasing returns nothing so the deterministic headline is used.
        stubOllamaSequence(
                Map.of("response", "QUOTE_CONVERSION | 30 | NONE"),
                Map.of("response", ""));

        InboxAnswer answer = service.ask("what share of our quotes turned into bookings?");

        assertThat(answer.metric()).isEqualTo(InboxMetric.QUOTE_CONVERSION);
        assertThat(answer.answer()).contains("40.0%").contains("4 of 10").contains("30 days");
        assertThat(answer.matched()).isTrue();
    }

    @Test
    void ask_quoteConversion_noQuotes_saysNoRateYet() {
        when(analyticsService.summarise(30)).thenReturn(summaryWithConversion(0, 0));
        stubOllamaSequence(
                Map.of("response", "QUOTE_CONVERSION | 30 | NONE"),
                Map.of("response", ""));

        InboxAnswer answer = service.ask("our quote conversion rate?");

        // No quotes → never show "—" or 0%; say there's no rate yet.
        assertThat(answer.answer()).contains("No quotes were sent");
        assertThat(answer.metric()).isEqualTo(InboxMetric.QUOTE_CONVERSION);
    }

    // ---- platform independence: the figures never follow the JVM default locale or line separator ----

    @Test
    @ResourceLock(Resources.LOCALE)
    void ask_underNonEnglishDefaultLocale_headlineStillUsesDecimalPoint() {
        when(analyticsService.summarise(30)).thenReturn(summaryWithBacklog(4, 1, 0));
        stubOllamaSequence(
                Map.of("response", "JUNK_COUNT | 30 | NONE"),
                Map.of("response", ""));

        InboxAnswer answer = withDefaultLocale(RU, () -> service.ask("how much junk did we get?"));

        // ru-RU would render %.1f as "20,0" — the headline must stay "20.0%" on every machine.
        assertThat(answer.answer()).contains("(20.0% of all mail)").doesNotContain("20,0");
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void ask_underNonEnglishDefaultLocale_slaBreachRateUsesDecimalPoint() {
        when(analyticsService.summarise(30)).thenReturn(summaryWithBacklog(5, 0, 1));
        stubOllamaSequence(
                Map.of("response", "SLA_BREACHES | 30 | NONE"),
                Map.of("response", ""));

        InboxAnswer answer = withDefaultLocale(RU, () -> service.ask("how many SLA breaches?"));

        assertThat(answer.answer()).contains("(20.0% of relevant tickets)");
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    @SuppressWarnings("unchecked")
    void ask_underNonEnglishDefaultLocale_phrasingPromptFactsUseDecimalPointAndLf() {
        when(analyticsService.summarise(30)).thenReturn(summaryWithBacklog(5, 0, 1));
        stubOllamaSequence(
                Map.of("response", "BOTTLENECKS | 30 | NONE"),
                Map.of("response", "Five tickets are waiting at NEW."));

        withDefaultLocale(RU, () -> service.ask("where are we getting stuck?"));

        ArgumentCaptor<Object> bodies = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec, times(2)).bodyValue(bodies.capture());
        String phrasingPrompt = (String) ((Map<String, Object>) bodies.getAllValues().get(1)).get("prompt");
        assertThat(phrasingPrompt)
                .contains("  NEW: 5\n  PROCESSING: 0\n")
                .contains("SLA breaches: 1 (20.0% of relevant)\nAverage first-response time: —")
                .doesNotContain("\r");
    }

    @Test
    void ask_breakerOpen_returnsGuidanceWithoutCallingTheModel() {
        circuitBreaker.transitionToOpenState();

        InboxAnswer answer = service.ask("how many RFQs?");

        // Routing degrades to UNKNOWN → the user gets the capability guidance, never a wrong number.
        assertThat(answer.matched()).isFalse();
        assertThat(answer.answer()).contains("I can answer questions about your inbox");
    }

    @Test
    void ask_unknownQuestion_returnsGuidance() {
        stubOllamaSequence(Map.of("response", "UNKNOWN | 30 | NONE"));

        InboxAnswer answer = service.ask("what is the meaning of life?");

        assertThat(answer.matched()).isFalse();
        assertThat(answer.answer()).contains("How many RFQs");
    }

    @Test
    void ask_blankQuestion_returnsGuidanceWithoutTouchingTheModel() {
        InboxAnswer answer = service.ask("   ");

        assertThat(answer.matched()).isFalse();
        assertThat(answer.answer()).contains("I can answer questions about your inbox");
    }

    @Test
    void isEnabled_defaultsTrue() {
        when(settingsService.isFeatureEnabled(InboxQueryService.FEATURE_KEY, true)).thenReturn(true);
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void ask_nullQuestion_returnsGuidanceWithoutTouchingTheModel() {
        InboxAnswer answer = service.ask(null);

        assertThat(answer.question()).isNull();
        assertThat(answer.matched()).isFalse();
        assertThat(answer.periodDays()).isEqualTo(AnalyticsService.DEFAULT_PERIOD_DAYS);
        assertThat(answer.answer()).contains("I can answer questions about your inbox");
        assertThat(meterRegistry.find("smartfreight_inbox_queries_total").counter()).isNull();
    }

    @Test
    void ask_typeBreakdown_noUnclassified_listsEveryTypeWithoutAnUnclassifiedLine() {
        when(analyticsService.summarise(30)).thenReturn(summaryWithType(TicketType.RFQ, 3));
        stubOllamaSequence(Map.of("response", "TYPE_BREAKDOWN | 30"), Map.of("response", ""));

        InboxAnswer answer = service.ask("emails by type?");

        assertThat(answer.answer()).isEqualTo("Relevant emails by type over the last 30 days — RFQ: 3, "
                + "STATUS_UPDATE: 0, BOOKING_CONFIRM: 0, INVOICE_QUERY: 0, DOCUMENT_REQUEST: 0, GENERAL: 0.");
    }

    // ---- characterisation: the deterministic headline of every metric ----
    // Phrasing returns nothing here, so the answer is exactly the headline sentence the service builds.

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            EMAILS_RECEIVED  | You received 20 emails in the last 30 days (16 relevant, 4 junk).
            RELEVANT_COUNT   | You received 16 relevant business emails in the last 30 days (junk excluded).
            JUNK_COUNT       | 4 junk emails were filtered out in the last 30 days (20.0% of all mail).
            TYPE_COUNT RFQ   | You received 5 RFQ emails in the last 30 days.
            TYPE_BREAKDOWN   | Relevant emails by type over the last 30 days — RFQ: 5, STATUS_UPDATE: 3, Unclassified: 2.
            STATUS_BREAKDOWN | Tickets by status over the last 30 days — RECEIVED: 1, NEW: 0, PROCESSING: 0, DRAFT_READY: 6, FAILED: 2, RETRY_LATER: 0, IRRELEVANT: 4, CLOSED: 5, AUTO_SENT: 2.
            REPLIES_SENT     | You sent 8 replies in the last 30 days (6 by an operator, 2 automatically).
            AUTOMATION_RATE  | 25.0% of replies were sent automatically in the last 30 days (2 of 8).
            AVG_RESPONSE_TIME| Your average first-response time over the last 30 days was 1h 30m.
            SLA_BREACHES     | 2 tickets breached their SLA in the last 30 days (12.5% of relevant tickets).
            QUOTE_CONVERSION | 40.0% of quotes turned into bookings in the last 30 days (4 of 10 quotes booked).
            BOTTLENECKS      | Over the last 30 days the biggest backlog is 6 ticket(s) at the DRAFT_READY stage; 2 SLA breaches.
            """)
    void ask_everyMetric_headlineFromTheRealFigures(String route, String expectedHeadline) {
        when(analyticsService.summarise(30)).thenReturn(richSummary());
        stubOllamaSequence(Map.of("response", route + " | 30"), Map.of("response", ""));

        InboxAnswer answer = service.ask("question");

        assertThat(answer.answer()).isEqualTo(expectedHeadline);
        assertThat(answer.periodDays()).isEqualTo(30);
    }

    @Test
    void ask_bottlenecks_nothingOpen_saysNothingIsBackedUp() {
        when(analyticsService.summarise(30)).thenReturn(summaryWithType(TicketType.RFQ, 0));
        stubOllamaSequence(Map.of("response", "BOTTLENECKS | 30"), Map.of("response", ""));

        InboxAnswer answer = service.ask("where are we stuck?");

        assertThat(answer.answer()).isEqualTo(
                "Over the last 30 days nothing is backed up — no open tickets are piling up at any stage.");
    }

    @Test
    void ask_phrasingPromptCarriesTheQuestionAndTheFigures() {
        when(analyticsService.summarise(30)).thenReturn(richSummary());
        stubOllamaSequence(Map.of("response", "BOTTLENECKS | 30"), Map.of("response", "Mostly drafts waiting."));

        InboxAnswer answer = service.ask("  where are we stuck?  ");

        assertThat(answer.answer()).isEqualTo("Mostly drafts waiting.");
        List<Map<String, Object>> bodies = capturedBodies(2);
        assertThat(bodies.get(0)).containsEntry("model", "qwen2.5:7b").containsEntry("stream", false);
        assertThat(bodies.get(1)).containsEntry("model", "llama3.1:8b");
        assertThat((String) bodies.get(1).get("prompt"))
                .contains("Question: where are we stuck?\n")
                .contains("""
                        Backlog by open stage (newest work first):
                          RECEIVED: 1
                          NEW: 0
                          PROCESSING: 0
                          DRAFT_READY (awaiting operator): 6
                          RETRY_LATER: 0
                          FAILED: 2
                        SLA breaches: 2 (12.5% of relevant)
                        Average first-response time: 1h 30m""");
    }

    @Test
    void ask_tenantRoutingPrompt_getsCatalogueAndStrippedQuestion() {
        when(settingsService.getString(eq(InboxQueryService.KEY_QUERY_PROMPT), anyString()))
                .thenReturn("Q={question} C={catalogue}");
        stubOllamaSequence(Map.of("response", "UNKNOWN"));

        service.ask("  how many?  ");

        assertThat(capturedBodies(1).get(0))
                .containsEntry("prompt", "Q=how many? C=" + InboxMetric.catalogue());
    }

    @Test
    void ask_countsEveryRoutedQuestionByMetric() {
        stubOllamaSequence(Map.of("response", "UNKNOWN"));

        service.ask("what is the meaning of life?");

        assertThat(meterRegistry.get("smartfreight_inbox_queries_total").tag("metric", "UNKNOWN")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void ask_blankQuestion_isNotCounted() {
        service.ask("");

        assertThat(meterRegistry.find("smartfreight_inbox_queries_total").counter()).isNull();
    }

    @Test
    void parseIntent_periodOutsideTheAllowedSet_fallsBackToDefault() {
        assertThat(InboxQueryService.parseIntent("EMAILS_RECEIVED | 14 | NONE").periodDays())
                .isEqualTo(AnalyticsService.DEFAULT_PERIOD_DAYS);
        assertThat(InboxQueryService.parseIntent("EMAILS_RECEIVED | 90 | NONE").periodDays()).isEqualTo(90);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ask_modelReturnsNoBody_degradesToGuidance() {
        stubOllamaChain();
        when(responseSpec.bodyToMono(Map.class)).thenReturn((Mono) Mono.empty());

        InboxAnswer answer = service.ask("how many RFQs?");

        assertThat(answer.matched()).isFalse();
        assertThat(answer.answer()).contains("I can answer questions about your inbox");
    }

    @Test
    void ask_modelCallFails_degradesToGuidance() {
        stubOllamaChain();
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.error(new IllegalStateException("connection refused")));

        InboxAnswer answer = service.ask("how many RFQs?");

        assertThat(answer.matched()).isFalse();
        assertThat(answer.answer()).contains("I can answer questions about your inbox");
    }

    // ---- helpers ----

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubOllamaChain() {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> capturedBodies(int calls) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec, times(calls)).bodyValue(captor.capture());
        return captor.getAllValues().stream().map(b -> (Map<String, Object>) b).toList();
    }

    /**
     * 20 tickets (4 junk), 8 replies (2 automatic), 2 SLA breaches, 90 min average response,
     * 4 of 10 quotes booked — round figures, so every headline above can be checked by hand.
     */
    private static AnalyticsSummary richSummary() {
        Map<TicketStatus, Long> byStatus = zeroStatuses();
        byStatus.put(TicketStatus.RECEIVED, 1L);
        byStatus.put(TicketStatus.DRAFT_READY, 6L);
        byStatus.put(TicketStatus.FAILED, 2L);
        byStatus.put(TicketStatus.IRRELEVANT, 4L);
        byStatus.put(TicketStatus.CLOSED, 5L);
        byStatus.put(TicketStatus.AUTO_SENT, 2L);
        Map<TicketType, Long> byType = new EnumMap<>(TicketType.class);
        byType.put(TicketType.RFQ, 5L);
        byType.put(TicketType.STATUS_UPDATE, 3L);
        return new AnalyticsSummary(30, byStatus, byType, 2L, 6L, 2L, 2L, 5400.0,
                List.<DayVolume>of(), List.<Long>of(), 0L, 0L, 10L, 4L);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @SafeVarargs
    private void stubOllamaSequence(Map<String, Object>... responses) {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        Mono<Map>[] monos = new Mono[responses.length];
        for (int i = 0; i < responses.length; i++) {
            monos[i] = Mono.just(responses[i]);
        }
        if (monos.length == 1) {
            when(responseSpec.bodyToMono(Map.class)).thenReturn(monos[0]);
        } else {
            when(responseSpec.bodyToMono(Map.class)).thenReturn(monos[0], java.util.Arrays.copyOfRange(monos, 1, monos.length));
        }
    }

    /** Minimal summary carrying one populated ticket type, enough for the metric assertions. */
    private static AnalyticsSummary summaryWithType(TicketType type, long count) {
        Map<TicketType, Long> byType = zeroTypes();
        byType.put(type, count);
        return new AnalyticsSummary(30, zeroStatuses(), byType, 0L, 0L, 0L, 0L, null,
                List.<DayVolume>of(), List.<Long>of(), 0L, 0L, 0L, 0L);
    }

    /** Minimal summary with NEW and IRRELEVANT tickets plus SLA breaches, for the locale assertions. */
    private static AnalyticsSummary summaryWithBacklog(long newTickets, long junk, long slaBreached) {
        Map<TicketStatus, Long> byStatus = zeroStatuses();
        byStatus.put(TicketStatus.NEW, newTickets);
        byStatus.put(TicketStatus.IRRELEVANT, junk);
        return new AnalyticsSummary(30, byStatus, zeroTypes(), 0L, 0L, 0L, slaBreached, null,
                List.<DayVolume>of(), List.<Long>of(), 0L, 0L, 0L, 0L);
    }

    private static Map<TicketStatus, Long> zeroStatuses() {
        Map<TicketStatus, Long> byStatus = new EnumMap<>(TicketStatus.class);
        for (TicketStatus s : TicketStatus.values()) {
            byStatus.put(s, 0L);
        }
        return byStatus;
    }

    private static Map<TicketType, Long> zeroTypes() {
        Map<TicketType, Long> byType = new EnumMap<>(TicketType.class);
        for (TicketType t : TicketType.values()) {
            byType.put(t, 0L);
        }
        return byType;
    }

    /** Runs the call with every default-locale category switched, restoring them afterwards. */
    private static <T> T withDefaultLocale(Locale locale, Supplier<T> call) {
        Locale def = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(locale);
        try {
            return call.get();
        } finally {
            Locale.setDefault(def);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    /** Minimal summary carrying quote-conversion figures, enough for the QUOTE_CONVERSION assertions. */
    private static AnalyticsSummary summaryWithConversion(long quotesSent, long quotesConverted) {
        return new AnalyticsSummary(30, zeroStatuses(), zeroTypes(), 0L, 0L, 0L, 0L, null,
                List.<DayVolume>of(), List.<Long>of(), 0L, 0L, quotesSent, quotesConverted);
    }
}
