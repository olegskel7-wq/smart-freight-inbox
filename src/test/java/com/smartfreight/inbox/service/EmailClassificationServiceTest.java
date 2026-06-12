package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketType;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the answer-parsing contract of EmailClassificationService (Phase 1.4).
 *
 * The LLM call itself is covered by the E2E smoke test against live Ollama; here we pin
 * down how raw one-word answers map to results, in both typed and binary (flag off) modes.
 */
class EmailClassificationServiceTest {

    // --- typed mode (feature.ticket-type on) ---

    @ParameterizedTest
    @EnumSource(TicketType.class)
    void typed_typeName_mapsToRelevantWithThatType(TicketType type) {
        ClassificationResult result = EmailClassificationService.parseAnswer(type.name(), true);

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isEqualTo(type);
    }

    @Test
    void typed_irrelevant_winsOverTypeMatching() {
        ClassificationResult result = EmailClassificationService.parseAnswer("IRRELEVANT", true);

        assertThat(result.relevant()).isFalse();
        assertThat(result.type()).isNull();
    }

    @Test
    void typed_answerWithTrailingText_stillMatches() {
        // Models occasionally elaborate despite the one-word instruction.
        ClassificationResult result = EmailClassificationService.parseAnswer("RFQ - THE CUSTOMER ASKS FOR A RATE", true);

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isEqualTo(TicketType.RFQ);
    }

    @Test
    void typed_bareRelevant_failsOpenToGeneral() {
        ClassificationResult result = EmailClassificationService.parseAnswer("RELEVANT", true);

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isEqualTo(TicketType.GENERAL);
    }

    @Test
    void typed_garbageAnswer_failsOpenToGeneral() {
        ClassificationResult result = EmailClassificationService.parseAnswer("I AM NOT SURE", true);

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isEqualTo(TicketType.GENERAL);
    }

    // --- binary mode (feature.ticket-type off, pre-1.4 contract) ---

    @Test
    void binary_relevant_mapsToRelevantWithoutType() {
        ClassificationResult result = EmailClassificationService.parseAnswer("RELEVANT", false);

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isNull();
    }

    @Test
    void binary_irrelevant_mapsToIrrelevant() {
        ClassificationResult result = EmailClassificationService.parseAnswer("IRRELEVANT", false);

        assertThat(result.relevant()).isFalse();
        assertThat(result.type()).isNull();
    }

    @Test
    void binary_garbageAnswer_failsOpenWithoutType() {
        ClassificationResult result = EmailClassificationService.parseAnswer("MAYBE", false);

        assertThat(result.relevant()).isTrue();
        assertThat(result.type()).isNull();
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
        EmailClassificationService service = new EmailClassificationService(
                webClient, settingsService, breaker, new SimpleMeterRegistry(), "qwen2.5:7b");

        assertThatThrownBy(() -> service.classify("Container inquiry", "Where is MSCU1234567?"))
                .isInstanceOf(CallNotPermittedException.class);

        verifyNoInteractions(webClient);
    }
}
