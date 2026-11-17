package com.smartfreight.inbox.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    private final LlmClient llmClient;

    InboxQueryOllamaClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** One generate call, returning the response text. Empty when the model gives nothing usable. */
    Optional<String> generate(String model, String prompt) {
        try {
            return llmClient.generate("inbox-query", model, prompt, OLLAMA_TIMEOUT)
                    .map(String::trim)
                    .filter(text -> !text.isEmpty());

        } catch (CallNotPermittedException e) {
            log.warn("Ollama circuit breaker open — inbox query degraded to deterministic answer");
            return Optional.empty();
        } catch (Exception e) {
            log.error("Inbox query Ollama call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
