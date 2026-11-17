package com.smartfreight.inbox.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.smartfreight.inbox.config.ResilienceConfig;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Completions on the cloud LLM (Claude over the Anthropic API) for a {@code llm.provider=cloud} tenant.
 * Reached only through {@link LlmClient}; the request shape is the one measured in L1 — the prompt as one
 * user message, the model and effort by role (see {@link CloudLlmProperties}).
 *
 * <p>One platform API key ({@code ANTHROPIC_API_KEY}, a file in SECRETS_DIR). Without it the client is
 * not configured: {@link #isConfigured()} is false, a tenant cannot be switched to cloud, and a call
 * fails like an unreachable endpoint. One circuit breaker ({@code anthropic}) for every cloud tenant,
 * since they share the endpoint; an open breaker surfaces as {@link CallNotPermittedException}, exactly
 * as Ollama's does, so the callers park or degrade the same way.
 */
@Component
public class CloudLlmClient {

    private static final Logger log = LoggerFactory.getLogger(CloudLlmClient.class);

    /** The pipeline's operation name for the reply draft — the one role that uses {@code draftModel}. */
    static final String DRAFTING = "drafting";
    private static final long SHORT_MAX_TOKENS = 2048;
    private static final long DRAFT_MAX_TOKENS = 4096;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final AnthropicClient client;
    private final CloudLlmProperties properties;
    private final CircuitBreaker breaker;
    private final OllamaClientProvider usageMeter;
    private final MeterRegistry meterRegistry;

    @Autowired
    public CloudLlmClient(@Value("${ANTHROPIC_API_KEY:}") String apiKey,
                          CloudLlmProperties properties,
                          CircuitBreakerRegistry breakerRegistry,
                          OllamaClientProvider usageMeter,
                          MeterRegistry meterRegistry) {
        this(apiKey, null, properties, breakerRegistry, usageMeter, meterRegistry);
    }

    /** {@code baseUrl} null = the Anthropic API; tests point it at a local stub. */
    CloudLlmClient(String apiKey, String baseUrl, CloudLlmProperties properties,
                   CircuitBreakerRegistry breakerRegistry, OllamaClientProvider usageMeter,
                   MeterRegistry meterRegistry) {
        this.client = apiKey == null || apiKey.isBlank() ? null : buildClient(apiKey.trim(), baseUrl);
        this.properties = properties;
        this.breaker = breakerRegistry.circuitBreaker(ResilienceConfig.ANTHROPIC_BREAKER, ResilienceConfig.ANTHROPIC_BREAKER);
        this.usageMeter = usageMeter;
        this.meterRegistry = meterRegistry;
    }

    private static AnthropicClient buildClient(String apiKey, String baseUrl) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                // One quick retry for a 429/5xx blip; the pipeline's own retry loop handles the rest
                // (two SDK retries could triple the wait past the pipeline's timeouts).
                .maxRetries(1);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    /** Whether the platform API key is set — the precondition for switching a tenant to cloud. */
    public boolean isConfigured() {
        return client != null;
    }

    /**
     * One completion for the CURRENT tenant, metered by tokens and by cost.
     *
     * @param operation the pipeline role ({@code drafting} gets the draft model and more effort)
     * @return the reply text, possibly empty (a refusal or an answer cut at max tokens is logged, and the
     *         caller's own fallback handles an empty or unusable answer)
     * @throws CallNotPermittedException when the breaker is open (no call is made)
     * @throws IllegalStateException     when no API key is configured
     */
    public String generate(String operation, String prompt, Duration timeout) {
        if (client == null) {
            throw new IllegalStateException("Cloud LLM requested but ANTHROPIC_API_KEY is not configured");
        }
        boolean draft = DRAFTING.equals(operation);
        String model = draft ? properties.draftModel() : properties.shortModel();
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(draft ? DRAFT_MAX_TOKENS : SHORT_MAX_TOKENS)
                .outputConfig(OutputConfig.builder()
                        .effort(draft ? OutputConfig.Effort.MEDIUM : OutputConfig.Effort.LOW)
                        .build())
                .addUserMessage(prompt)
                .build();
        RequestOptions options = RequestOptions.builder().timeout(timeout).build();

        Message response = breaker.executeSupplier(() -> client.messages().create(params, options));

        long input = response.usage().inputTokens();
        long output = response.usage().outputTokens();
        usageMeter.recordTokens(operation, model, input, output);
        recordCost(operation, model, input, output);

        response.stopReason()
                .filter(reason -> reason.equals(StopReason.REFUSAL) || reason.equals(StopReason.MAX_TOKENS))
                .ifPresent(reason -> log.warn("Cloud LLM {} call ended with {}", operation, reason));
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .collect(Collectors.joining());
    }

    private void recordCost(String operation, String model, long input, long output) {
        CloudLlmProperties.Price price = properties.prices().get(model);
        if (price == null) {
            return;
        }
        BigDecimal usd = price.input().multiply(BigDecimal.valueOf(input))
                .add(price.output().multiply(BigDecimal.valueOf(output)))
                .divide(MILLION, 10, RoundingMode.HALF_UP);
        Counter.builder("smartfreight_llm_cost_usd_total")
                .description("Estimated cloud LLM spend in USD per tenant, model and operation (list prices)")
                .tags("tenant", TenantContext.metricTag(), "model", model, "operation", operation)
                .register(meterRegistry)
                .increment(usd.doubleValue());
    }
}
