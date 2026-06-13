package com.smartfreight.inbox.actuator;

import com.smartfreight.inbox.service.OllamaClientProvider;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Health indicator for the Ollama AI server.
 *
 * Used by the Spring Boot Actuator endpoint {@code /actuator/health}
 * to check the availability of critical dependencies.
 *
 * Statuses:
 * — UP: Ollama is running and both models (Qwen + Llama) are loaded
 * — OUT_OF_SERVICE: Ollama is reachable but models are still loading (warmup)
 * — DOWN: Ollama is unreachable or a network error occurred
 *
 * Use cases:
 * — Grafana/Prometheus monitoring
 * — Kubernetes readiness probe
 * — Load balancer — route traffic only to healthy instances
 */
@Component
public class OllamaHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(OllamaHealthIndicator.class);

    /** Container extraction model name (Qwen 2.5). */
    @Value("${ollama.extraction-model:qwen2.5:7b}")
    private String extractionModel;

    /** Reply generation model name (Llama 3.1). */
    @Value("${ollama.drafting-model:llama3.1:8b}")
    private String draftingModel;

    private final OllamaClientProvider ollamaClients;

    public OllamaHealthIndicator(OllamaClientProvider ollamaClients) {
        this.ollamaClients = ollamaClients;
    }

    /**
     * Checks the health of the Ollama server.
     *
     * Algorithm:
     * 1. GET /api/tags — Ollama returns the list of loaded models
     * 2. Check whether both model names are present in the response
     * 3. Both found → UP (fully ready)
     * 4. At least one missing → OUT_OF_SERVICE (models are still loading)
     * 5. Ollama did not respond or network error → DOWN
     *
     * @return Health object with status and additional details
     */
    @Override
    public Health health() {
        try {
            // This probe is about the PLATFORM instance — system context resolves the
            // platform URL regardless of which thread (web, scheduler) asks for health.
            WebClient ollamaWebClient = TenantContext.callAsSystem(ollamaClients::client);
            String response = ollamaWebClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));

            if (response != null
                    && response.contains(extractionModel)
                    && response.contains(draftingModel)) {
                log.info("Ollama is healthy, both models loaded");
                return Health.up()
                        .withDetail("status", "Ready")
                        .withDetail("models", extractionModel + ", " + draftingModel)
                        .build();
            } else {
                log.warn("Ollama models are not yet loaded, warming up...");
                return Health.outOfService()
                        .withDetail("status", "Warming up")
                        .withDetail("message", "Models are loading")
                        .build();
            }

        } catch (Exception e) {
            log.warn("Ollama health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("status", "Unavailable")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
