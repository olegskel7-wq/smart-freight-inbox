package com.smartfreight.inbox.service;

import com.smartfreight.inbox.config.ResilienceConfig;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The cloud provider against a local stub of the Anthropic API: the real SDK sends the request, so the
 * tests pin what goes over the wire (model, max tokens, effort, the prompt, the key) and what comes back.
 */
class CloudLlmClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockWebServer server;
    private CircuitBreakerRegistry breakers;
    private OllamaClientProvider usageMeter;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        breakers = CircuitBreakerRegistry.of(Map.of(ResilienceConfig.ANTHROPIC_BREAKER, CircuitBreakerConfig.ofDefaults()));
        usageMeter = mock(OllamaClientProvider.class);
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private CloudLlmClient client(String apiKey, String draftModel) {
        CloudLlmProperties properties = new CloudLlmProperties("claude-haiku-5-5", draftModel, Map.of(
                "claude-haiku-5-5", new CloudLlmProperties.Price(new BigDecimal("0.10"), new BigDecimal("0.50"))));
        return new CloudLlmClient(apiKey, server.url("/").toString(), properties, breakers, usageMeter, meters);
    }

    private static MockResponse message(String stopReason, String contentJson, long in, long out) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-5-5",
                 "content":%s,"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":%d,"output_tokens":%d}}""".formatted(contentJson, stopReason, in, out));
    }

    private JsonNode sentBody(RecordedRequest request) {
        return JSON.readTree(request.getBody().readUtf8());
    }

    @Test
    void shortRole_sendsThePromptToTheShortModelAtLowEffort_andMetersTokensAndCost() throws Exception {
        server.enqueue(message("end_turn", "[{\"type\":\"text\",\"text\":\"RFQ|HIGH|NEGATIVE\"}]", 1200, 8));

        String text = TenantContext.callAs(7L, () -> client("sk-ant-api03-test", "claude-haiku-5-5")
                .generate("classification", "Classify this email", TIMEOUT));

        assertThat(text).isEqualTo("RFQ|HIGH|NEGATIVE");
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getPath()).isEqualTo("/v1/messages");
        // SDK 3.x sends the API key as a static bearer credential.
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sk-ant-api03-test");
        JsonNode body = sentBody(request);
        assertThat(body.get("model").asString()).isEqualTo("claude-haiku-5-5");
        assertThat(body.get("max_tokens").asLong()).isEqualTo(2048);
        assertThat(body.get("output_config").get("effort").asString()).isEqualTo("low");
        assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("user");
        assertThat(body.get("messages").get(0).get("content").asString()).isEqualTo("Classify this email");
        verify(usageMeter).recordTokens("classification", "claude-haiku-5-5", 1200, 8);
        double cost = meters.get("smartfreight_llm_cost_usd_total")
                .tags("tenant", "7", "model", "claude-haiku-5-5", "operation", "classification").counter().count();
        assertThat(cost).isCloseTo((1200 * 0.10 + 8 * 0.50) / 1_000_000, within(1e-12));
    }

    @Test
    void draftRole_usesTheDraftModelAtMediumEffort_andJoinsTheTextBlocks() throws Exception {
        server.enqueue(message("end_turn",
                "[{\"type\":\"text\",\"text\":\"Dear Sue,\"},{\"type\":\"text\",\"text\":\" your box is at port.\"}]", 900, 60));

        String text = client("sk-ant-api03-test", "claude-sonnet-5-5").generate("drafting", "Write the reply", TIMEOUT);

        assertThat(text).isEqualTo("Dear Sue, your box is at port.");
        JsonNode body = sentBody(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(body.get("model").asString()).isEqualTo("claude-sonnet-5-5");
        assertThat(body.get("max_tokens").asLong()).isEqualTo(4096);
        assertThat(body.get("output_config").get("effort").asString()).isEqualTo("medium");
        // No price configured for that model: tokens are metered, cost is not.
        verify(usageMeter).recordTokens("drafting", "claude-sonnet-5-5", 900, 60);
        assertThat(meters.find("smartfreight_llm_cost_usd_total").counter()).isNull();
    }

    @Test
    void refusal_answersEmptyText_soTheCallersFallbackTakesOver() {
        server.enqueue(message("refusal", "[]", 300, 0));

        assertThat(client("sk-ant-api03-test", "claude-haiku-5-5").generate("extraction", "x", TIMEOUT)).isEmpty();
        verify(usageMeter).recordTokens("extraction", "claude-haiku-5-5", 300, 0);
    }

    @Test
    void serverError_isThrownAfterOneRetry_andNotMetered() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}"));

        assertThatThrownBy(() -> client("sk-ant-api03-test", "claude-haiku-5-5").generate("classification", "x", TIMEOUT))
                .isInstanceOf(RuntimeException.class);

        assertThat(server.getRequestCount()).isEqualTo(2);
        verify(usageMeter, never()).recordTokens(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void openBreaker_propagatesWithoutACall() {
        breakers.circuitBreaker(ResilienceConfig.ANTHROPIC_BREAKER).transitionToOpenState();

        assertThatThrownBy(() -> client("sk-ant-api03-test", "claude-haiku-5-5").generate("classification", "x", TIMEOUT))
                .isInstanceOf(CallNotPermittedException.class);

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void withoutAKey_isNotConfigured_andACallFailsLoudly() {
        CloudLlmClient unconfigured = client("  ", "claude-haiku-5-5");

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.generate("classification", "x", TIMEOUT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(server.getRequestCount()).isZero();
    }
}
