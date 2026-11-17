package com.smartfreight.inbox.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The one way the application asks a language model for text. Every completion goes through
 * {@link #generate}, which serves it from the CURRENT tenant's provider ({@link LlmProviderSettings}):
 * <ul>
 *   <li>{@code local} (default) — the tenant's Ollama endpoint and circuit breaker, {@code /api/generate};</li>
 *   <li>{@code cloud} — Claude over the Anthropic API ({@link CloudLlmClient}), the model chosen by the
 *       operation's role; the Ollama model name a caller passes applies to local only.</li>
 * </ul>
 * Either way the call is metered on {@code smartfreight_llm_calls_total} / {@code _tokens_total}
 * (ROADMAP LLM strategy, steps L2a/L2b).
 *
 * <p>Callers keep their own error handling: an open breaker surfaces as
 * {@link CallNotPermittedException} and any other failure as the original exception, so each caller
 * still decides whether to park the ticket, fall back or degrade.
 */
@Component
public class LlmClient {

    private final OllamaClientProvider ollamaClients;
    private final LlmProviderSettings providerSettings;
    private final CloudLlmClient cloudLlmClient;

    @Autowired
    public LlmClient(OllamaClientProvider ollamaClients, LlmProviderSettings providerSettings,
                     CloudLlmClient cloudLlmClient) {
        this.ollamaClients = ollamaClients;
        this.providerSettings = providerSettings;
        this.cloudLlmClient = cloudLlmClient;
    }

    /** Ollama only, whatever the tenant's setting — for tests of the callers. */
    public LlmClient(OllamaClientProvider ollamaClients) {
        this(ollamaClients, null, null);
    }

    /**
     * One non-streaming completion.
     *
     * @param operation usage-meter tag ({@code classification}, {@code drafting}, …)
     * @param model     the Ollama model name (a cloud tenant's model comes from {@link CloudLlmProperties})
     * @param prompt    the full prompt
     * @param timeout   how long to wait for the answer
     * @return the model's text, untrimmed; empty when the endpoint answered with no body. A completed
     *         call is metered either way, a failed one is not.
     * @throws CallNotPermittedException when the provider's breaker is open (no call is made)
     */
    public Optional<String> generate(String operation, String model, String prompt, Duration timeout) {
        if (providerSettings != null && providerSettings.isCloud()) {
            return Optional.of(cloudLlmClient.generate(operation, prompt, timeout));
        }
        return generateOnOllama(operation, model, prompt, timeout);
    }

    private Optional<String> generateOnOllama(String operation, String model, String prompt, Duration timeout) {
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
                .block(timeout));
        ollamaClients.recordUsage(operation, model, response);

        if (response == null) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(response.getOrDefault("response", "")));
    }
}
