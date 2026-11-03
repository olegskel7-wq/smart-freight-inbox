package com.smartfreight.inbox.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The Ollama call behind both "Ask your inbox" steps (routing and phrasing), on the CURRENT
 * tenant's endpoint and circuit breaker.
 *
 * <p>Unlike {@link OllamaDraftClient}, this is interactive and read-only: an open breaker or any
 * failure yields {@link Optional#empty()} (the caller degrades), it is never propagated.
 */
@Component
class InboxQueryOllamaClient {

    private static final Logger log = LoggerFactory.getLogger(InboxQueryOllamaClient.class);

    private static final Duration OLLAMA_TIMEOUT = Duration.ofSeconds(60);

    private final OllamaClientProvider ollamaClients;

    InboxQueryOllamaClient(OllamaClientProvider ollamaClients) {
        this.ollamaClients = ollamaClients;
    }

    /** One generate call, returning the response text. Empty when the model gives nothing usable. */
    Optional<String> generate(String model, String prompt) {
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
            ollamaClients.recordUsage("inbox-query", model, response);

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
}
