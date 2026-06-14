package com.smartfreight.inbox.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Produces text embeddings via the CURRENT tenant's Ollama endpoint (Phase 3.1).
 *
 * <p>Used by the {@link FeedbackLoopService} to embed captured corrections and (Session 2) the
 * incoming enquiry, so the feedback loop can rank past corrections by semantic similarity with
 * pgvector. The embedding model ({@code nomic-embed-text}) runs in the same local Ollama as the
 * drafting/extraction models, so corrections never leave the server.
 *
 * <p>Shares the per-tenant client + circuit breaker with {@link AiDraftingService} via
 * {@link OllamaClientProvider}. Unlike the reply pipeline, embeddings are an AUXILIARY concern:
 * a failure (including an OPEN breaker) returns {@link Optional#empty()} rather than propagating —
 * a missing embedding must never block capturing the correction or generating a draft. The example
 * is still stored without an embedding and can be backfilled later.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** Ollama embeddings-call timeout — much shorter than generation; embeddings are fast. */
    private static final Duration OLLAMA_TIMEOUT = Duration.ofSeconds(30);

    private final OllamaClientProvider ollamaClients;
    private final String embeddingModel;

    public EmbeddingService(OllamaClientProvider ollamaClients,
                            @Value("${ollama.embedding-model:nomic-embed-text}") String embeddingModel) {
        this.ollamaClients = ollamaClients;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Embeds the given text via Ollama {@code /api/embeddings} for the current tenant's endpoint.
     *
     * @param text the text to embed (e.g. an enquiry)
     * @return the embedding vector, or empty when the text is blank or the call fails / breaker is open
     */
    public Optional<float[]> embed(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", embeddingModel);
            requestBody.put("prompt", text);

            WebClient client = ollamaClients.client();
            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaClients.circuitBreaker().executeSupplier(() -> client
                    .post()
                    .uri("/api/embeddings")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(OLLAMA_TIMEOUT));

            if (response == null || !(response.get("embedding") instanceof List<?> raw) || raw.isEmpty()) {
                log.warn("Ollama returned no embedding for model {}", embeddingModel);
                return Optional.empty();
            }

            float[] vector = new float[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                vector[i] = ((Number) raw.get(i)).floatValue();
            }
            return Optional.of(vector);

        } catch (CallNotPermittedException e) {
            // Breaker OPEN — Ollama is known to be down. Embeddings are auxiliary, so degrade
            // gracefully (skip) instead of propagating, unlike the drafting pipeline.
            log.warn("Ollama circuit breaker is open — embedding skipped");
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Embedding call to Ollama failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Formats an embedding as a pgvector text literal (e.g. {@code "[0.1,0.2,0.3]"}) for the
     * native {@code ::vector} cast in {@link com.smartfreight.inbox.repository.TrainingExampleRepository}.
     *
     * @param vector the embedding
     * @return the pgvector literal
     */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
