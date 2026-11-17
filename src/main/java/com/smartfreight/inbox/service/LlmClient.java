package com.smartfreight.inbox.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The one way the application asks a language model for text. Every completion goes through
 * {@link #generate}: the CURRENT tenant's endpoint and circuit breaker, the call, and the usage
 * meter ({@code smartfreight_llm_calls_total} / {@code _tokens_total}).
 *
 * <p>Today it speaks Ollama's {@code /api/generate} only (ROADMAP LLM strategy, step L2a). A per-tenant
 * cloud provider (L2b) plugs in here, so callers will not change.
 *
 * <p>Callers keep their own error handling: an open breaker surfaces as
 * {@link CallNotPermittedException} and any other failure as the original exception, so each caller
 * still decides whether to park the ticket, fall back or degrade.
 */
@Component
public class LlmClient {

    private final OllamaClientProvider ollamaClients;

    public LlmClient(OllamaClientProvider ollamaClients) {
        this.ollamaClients = ollamaClients;
    }

    /**
     * One non-streaming completion.
     *
     * @param operation usage-meter tag ({@code classification}, {@code drafting}, …)
     * @param model     model name
     * @param prompt    the full prompt
     * @param timeout   how long to wait for the answer
     * @return the model's text, untrimmed; empty when the endpoint answered with no body. A completed
     *         call is metered either way, a failed one is not.
     * @throws CallNotPermittedException when the tenant's breaker is open (no call is made)
     */
    public Optional<String> generate(String operation, String model, String prompt, Duration timeout) {
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
