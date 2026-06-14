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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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

    private InboxQueryService service;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        // Resolve every prompt to the code-level default (arg 1) — un-customised tenant.
        lenient().when(settingsService.getString(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        circuitBreaker = CircuitBreaker.ofDefaults("inbox-query-test");
        lenient().when(ollamaClients.client()).thenReturn(webClient);
        lenient().when(ollamaClients.circuitBreaker()).thenReturn(circuitBreaker);
        service = new InboxQueryService(analyticsService, settingsService, ollamaClients,
                new SimpleMeterRegistry(), "qwen2.5:7b", "llama3.1:8b");
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

    // ---- helpers ----

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
        Map<TicketStatus, Long> byStatus = new EnumMap<>(TicketStatus.class);
        for (TicketStatus s : TicketStatus.values()) {
            byStatus.put(s, 0L);
        }
        Map<TicketType, Long> byType = new EnumMap<>(TicketType.class);
        for (TicketType t : TicketType.values()) {
            byType.put(t, 0L);
        }
        byType.put(type, count);
        return new AnalyticsSummary(30, byStatus, byType, 0L, 0L, 0L, 0L, null,
                List.<DayVolume>of(), List.<Long>of(), 0L, 0L);
    }
}
