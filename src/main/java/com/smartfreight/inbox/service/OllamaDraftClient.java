package com.smartfreight.inbox.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The drafting model's Ollama call, shared by every {@link AiDraftingService} draft: per-tenant
 * endpoint and circuit breaker, the no-signature instruction, sign-off stripping, the static
 * fallback on an empty or failed call, and the request metrics.
 */
@Component
public class OllamaDraftClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaDraftClient.class);

    /** Ollama generate-call timeout. */
    private static final Duration OLLAMA_TIMEOUT = Duration.ofSeconds(150);

    /** Draft generation model name — read from application.yaml. */
    private final String draftingModel;

    private final OllamaClientProvider ollamaClients;
    private final ReplySignatureService signatureService;
    private final Timer ollamaTimer;

    public OllamaDraftClient(OllamaClientProvider ollamaClients,
                             ReplySignatureService signatureService,
                             MeterRegistry meterRegistry,
                             @Value("${ollama.drafting-model:llama3.1:8b}") String draftingModel) {
        this.ollamaClients = ollamaClients;
        this.signatureService = signatureService;
        this.draftingModel = draftingModel;
        this.ollamaTimer = Timer.builder("smartfreight_ollama_request_seconds")
                .description("Ollama response time per model")
                .tag("model", "llama3.1:8b")
                .tag("operation", "drafting")
                .register(meterRegistry);
    }

    /**
     * Sends a generate request to Ollama for the CURRENT tenant's endpoint and
     * returns the response text, falling back to a static draft on an empty/failed call.
     *
     * @param prompt        the fully substituted prompt
     * @param fallbackDraft static draft returned when the call yields nothing (precomputed by the
     *                      caller — pure string-building, no I/O)
     * @return the model's reply, or the fallback draft
     * @throws CallNotPermittedException when the breaker is OPEN — propagated so the caller can
     *         decide (the reply pipeline parks the ticket; the proactive pollers skip the entry)
     */
    public String generate(String prompt, String fallbackDraft) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", draftingModel);
            // Appended to every template (stored prompts included): drafts are unsigned, the real
            // signature is added at send time by ReplySignatureService.
            requestBody.put("prompt", prompt + "\n\n" + signatureService.draftingInstruction());
            requestBody.put("stream", false);

            log.debug("Sending drafting request to Ollama model {}", draftingModel);

            long start = System.nanoTime();
            // Client + breaker resolved per call for the CURRENT tenant's endpoint.
            WebClient ollamaWebClient = ollamaClients.client();
            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaClients.circuitBreaker().executeSupplier(() -> ollamaWebClient
                    .post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(OLLAMA_TIMEOUT));
            ollamaTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            ollamaClients.recordUsage("drafting", draftingModel, response);

            if (response == null) {
                log.warn("Ollama returned null response during drafting");
                return fallbackDraft;
            }

            // Small local models still sign off ("Best regards, [Your Name]") despite the instruction.
            String draft = ReplySignatureService.stripSignOff(String.valueOf(response.getOrDefault("response", "")));
            if (draft.isEmpty()) {
                log.warn("Ollama returned empty draft");
                return fallbackDraft;
            }

            log.info("Draft generated successfully ({} chars)", draft.length());
            return draft;

        } catch (CallNotPermittedException e) {
            // Breaker OPEN = Ollama is known to be down. Propagate so the pipeline parks the
            // ticket as RETRY_LATER instead of serving the static fallback draft.
            log.warn("Ollama circuit breaker is open — drafting call not attempted");
            throw e;
        } catch (Exception e) {
            log.error("Drafting call to Ollama failed: {}", e.getMessage());
            return fallbackDraft;
        }
    }
}
