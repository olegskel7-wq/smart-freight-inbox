package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.entity.enums.Urgency;
import com.smartfreight.inbox.service.EmailClassificationService.ClassificationResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the answer-parsing contract of EmailClassificationService (Phase 1.4).
 *
 * The LLM call itself is covered by the E2E smoke test against live Ollama; here we pin
 * down how raw one-word answers map to results.
 */
class EmailClassificationServiceTest {

    // --- typed mode (feature.ticket-type on) ---

    @ParameterizedTest
    @EnumSource(TicketType.class)
    void typed_typeName_mapsToRelevantWithThatType(TicketType type) {
        ClassificationResult result = EmailClassificationService.parseAnswer(type.name());

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isEqualTo(type);
    }

    @Test
    void typed_irrelevant_winsOverTypeMatching() {
        ClassificationResult result = EmailClassificationService.parseAnswer("IRRELEVANT");

        assertThat(result.relevant()).isFalse();
        assertThat(result.type()).isNull();
    }

    @Test
    void typed_answerWithTrailingText_stillMatches() {
        // Models occasionally elaborate despite the one-word instruction.
        ClassificationResult result = EmailClassificationService.parseAnswer("RFQ - THE CUSTOMER ASKS FOR A RATE");

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isEqualTo(TicketType.RFQ);
    }

    @Test
    void typed_bareRelevant_failsOpenToGeneral() {
        ClassificationResult result = EmailClassificationService.parseAnswer("RELEVANT");

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isEqualTo(TicketType.GENERAL);
    }

    @Test
    void typed_garbageAnswer_failsOpenToGeneral() {
        ClassificationResult result = EmailClassificationService.parseAnswer("I AM NOT SURE");

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isEqualTo(TicketType.GENERAL);
    }


    // --- Phase 3.10: smart routing (typed + feature.smart-routing → TYPE|URGENCY|SENTIMENT) ---

    @Test
    void routing_parsesTypeUrgencyAndSentiment() {
        ClassificationResult result = EmailClassificationService.parseAnswer("RFQ|HIGH|NEGATIVE", true);

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isEqualTo(TicketType.RFQ);
        assertThat(result.urgency()).isEqualTo(Urgency.HIGH);
        assertThat(result.sentimentNegative()).isTrue();
    }

    @Test
    void routing_irrelevant_shortCircuitsWithNoEscalation() {
        // Spam carries no urgency/sentiment even if the model appends fields.
        ClassificationResult result = EmailClassificationService.parseAnswer("IRRELEVANT|HIGH|NEGATIVE", true);

        assertThat(result.relevant()).isFalse();
        assertThat(result.urgency()).isEqualTo(Urgency.NORMAL);
        assertThat(result.sentimentNegative()).isFalse();
    }

    @Test
    void routing_missingFields_failOpenToNormalAndCalm() {
        // Model ignored the format and returned only the type — routing degrades, it does not error.
        ClassificationResult result = EmailClassificationService.parseAnswer("STATUS_UPDATE", true);

        assertThat(result.type()).isEqualTo(TicketType.STATUS_UPDATE);
        assertThat(result.urgency()).isEqualTo(Urgency.NORMAL);
        assertThat(result.sentimentNegative()).isFalse();
    }

    @Test
    void routing_unknownUrgency_defaultsToNormal() {
        ClassificationResult result = EmailClassificationService.parseAnswer("RFQ|WHENEVER|NEUTRAL", true);

        assertThat(result.urgency()).isEqualTo(Urgency.NORMAL);
        assertThat(result.sentimentNegative()).isFalse();
    }

    @Test
    void routing_positiveSentiment_isNotNegative() {
        ClassificationResult result = EmailClassificationService.parseAnswer("GENERAL|LOW|POSITIVE", true);

        assertThat(result.urgency()).isEqualTo(Urgency.LOW);
        assertThat(result.sentimentNegative()).isFalse();
    }

    @Test
    void routing_off_ignoresExtraFields() {
        // With smart routing off the pipe fields are not consulted — NORMAL/calm regardless.
        ClassificationResult result = EmailClassificationService.parseAnswer("RFQ|HIGH|NEGATIVE", false);

        assertThat(result.type()).isEqualTo(TicketType.RFQ);
        assertThat(result.urgency()).isEqualTo(Urgency.NORMAL);
        assertThat(result.sentimentNegative()).isFalse();
    }

    // isEscalated() is the single definition consumed by the SLA window and the auto-assign routing.
    @Test
    void isEscalated_trueOnHighUrgencyOrNegativeTone_falseOtherwise() {
        assertThat(new ClassificationResult(true, TicketType.RFQ, Urgency.HIGH, false).isEscalated()).isTrue();
        assertThat(new ClassificationResult(true, TicketType.RFQ, Urgency.NORMAL, true).isEscalated()).isTrue();
        assertThat(new ClassificationResult(true, TicketType.RFQ, Urgency.NORMAL, false).isEscalated()).isFalse();
        assertThat(new ClassificationResult(true, TicketType.RFQ, Urgency.LOW, false).isEscalated()).isFalse();
    }

    // --- Phase 1.7: circuit breaker ---

    /**
     * Breaker OPEN must propagate (the pipeline parks the ticket) rather than fail open
     * to GENERAL — unlike a sporadic Ollama error, an open breaker means the verdict can
     * still be made properly on retry. No HTTP call is attempted.
     */
    @Test
    void classify_breakerOpen_propagatesCallNotPermitted() {
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.isFeatureEnabled(anyString(), anyBoolean())).thenReturn(true);
        WebClient webClient = mock(WebClient.class);
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("ollama-test");
        breaker.transitionToOpenState();
        // Phase 2.2 S3: client + breaker come from the provider, resolved per call.
        OllamaClientProvider ollamaClients = mock(OllamaClientProvider.class);
        when(ollamaClients.client()).thenReturn(webClient);
        when(ollamaClients.circuitBreaker()).thenReturn(breaker);
        EmailClassificationService service = new EmailClassificationService(
                ollamaClients, settingsService, new SimpleMeterRegistry(), "qwen2.5:7b");

        assertThatThrownBy(() -> service.classify("Container inquiry", "Where is MSCU1234567?"))
                .isInstanceOf(CallNotPermittedException.class);

        verifyNoInteractions(webClient);
        verify(ollamaClients, never()).recordUsage(any(), any(), any());
    }

    // ---- classify: the Ollama call -----------------------------------------------------

    /** The classifier over a stubbed Ollama, with feature.smart-routing on or off. */
    private static EmailClassificationService classifier(OllamaStub ollama, boolean smartRouting) {
        SettingsService settings = mock(SettingsService.class);
        when(settings.isFeatureEnabled(anyString(), anyBoolean())).thenReturn(smartRouting);
        return new EmailClassificationService(ollama.provider, settings, new SimpleMeterRegistry(), "qwen-test");
    }

    @Test
    void classify_smartRouting_asksForTypeUrgencyAndSentiment_andParsesTheAnswer() {
        OllamaStub ollama = new OllamaStub();
        ollama.answers(java.util.Map.of("response", " rfq|high|negative ", "done", true));

        ClassificationResult result = classifier(ollama, true).classify("Quote please", "Price for 1x40HC to Rotterdam?");

        assertThat(result.type()).isEqualTo(TicketType.RFQ);
        assertThat(result.urgency()).isEqualTo(Urgency.HIGH);
        assertThat(result.sentimentNegative()).isTrue();
        assertThat(ollama.sentPath()).isEqualTo("/api/generate");
        java.util.Map<?, ?> body = ollama.sentBody();
        assertThat(body.get("model")).isEqualTo("qwen-test");
        assertThat(body.get("stream")).isEqualTo(false);
        assertThat(body).hasSize(3);
        String prompt = (String) body.get("prompt");
        assertThat(prompt).contains("URGENCY").endsWith("Subject: Quote please\nEmail:\nPrice for 1x40HC to Rotterdam?");
        verify(ollama.provider).recordUsage(org.mockito.ArgumentMatchers.eq("classification"),
                org.mockito.ArgumentMatchers.eq("qwen-test"), any());
    }

    @Test
    void classify_smartRoutingOff_asksForTheTypeOnly() {
        OllamaStub ollama = new OllamaStub();
        ollama.answers(java.util.Map.of("response", "STATUS_UPDATE|HIGH|NEGATIVE", "done", true));

        ClassificationResult result = classifier(ollama, false).classify(null, "Where is MSCU1234567?");

        assertThat(result.type()).isEqualTo(TicketType.STATUS_UPDATE);
        assertThat(result.urgency()).isEqualTo(Urgency.NORMAL);
        assertThat(result.sentimentNegative()).isFalse();
        assertThat((String) ollama.sentBody().get("prompt")).doesNotContain("URGENCY").contains("Subject: \n");
    }

    @Test
    void classify_emptyOllamaBody_failsOpenToGeneral_andIsMetered() {
        OllamaStub ollama = new OllamaStub();
        ollama.answers(null);

        ClassificationResult result = classifier(ollama, true).classify("s", "Hello");

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isEqualTo(TicketType.GENERAL);
        verify(ollama.provider).recordUsage("classification", "qwen-test", null);
    }

    @Test
    void classify_failedCall_failsOpenToGeneral_andIsNotMetered() {
        OllamaStub ollama = new OllamaStub();
        ollama.fails(new RuntimeException("Connection refused"));

        ClassificationResult result = classifier(ollama, true).classify("s", "Hello");

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isEqualTo(TicketType.GENERAL);
        verify(ollama.provider, never()).recordUsage(any(), any(), any());
    }

    @Test
    void classify_blankBody_isIrrelevantWithoutACall() {
        OllamaStub ollama = new OllamaStub();

        assertThat(classifier(ollama, true).classify("s", " ").relevant()).isFalse();

        verifyNoInteractions(ollama.webClient);
    }
}
