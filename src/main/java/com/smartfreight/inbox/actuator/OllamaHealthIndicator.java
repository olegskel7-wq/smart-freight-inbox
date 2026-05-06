package com.smartfreight.inbox.actuator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Ollama AI serverio sveikatos indikatorius.
 *
 * Naudojamas Spring Boot Actuator galūne {@code /actuator/health}
 * kritinių priklausomybių pasiekiamumui tikrinti.
 *
 * Būsenos:
 * – UP: Ollama veikia ir abi modelės (Qwen + Llama) įkeltos
 * – OUT_OF_SERVICE: Ollama pasiekiama, bet modelės dar įkeliamos (warmup)
 * – DOWN: Ollama nepasiekiama arba įvyko tinklo klaida
 *
 * Naudojimas:
 * – Stebėsena Grafana/Prometheus
 * – Kubernetes readiness probe
 * – Apkrovos balansuotojas – maršrutizuoti srautą tik į sveikas instancijas
 */
@Component
public class OllamaHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(OllamaHealthIndicator.class);

    /** Konteinerių ištraukimo modelės pavadinimas (Qwen 2.5). */
    @Value("${ollama.extraction-model:qwen2.5:7b}")
    private String extractionModel;

    /** Atsakymų generavimo modelės pavadinimas (Llama 3.1). */
    @Value("${ollama.drafting-model:llama3.1:8b}")
    private String draftingModel;

    private final WebClient ollamaWebClient;

    public OllamaHealthIndicator(@Qualifier("ollamaWebClient") WebClient ollamaWebClient) {
        this.ollamaWebClient = ollamaWebClient;
    }

    /**
     * Tikrina Ollama serverio sveikatą.
     *
     * Algoritmas:
     * 1. GET /api/tags – Ollama grąžina įkeltų modelių sąrašą
     * 2. Tikriname, ar atsakyme yra abiejų modelių pavadinimai
     * 3. Abi rastos → UP (visiškai paruošta)
     * 4. Bent viena nerasta → OUT_OF_SERVICE (modelės dar įkeliamos)
     * 5. Ollama neatsakė ar tinklo klaida → DOWN
     *
     * @return Health objektas su būsena ir papildoma informacija
     */
    @Override
    public Health health() {
        try {
            String response = ollamaWebClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response != null
                    && response.contains(extractionModel)
                    && response.contains(draftingModel)) {
                log.info("Ollama is healthy, both models loaded");
                return Health.up()
                        .withDetail("status", "Ready")
                        .withDetail("models", extractionModel + ", " + draftingModel)
                        .build();
            } else {
                // Modelės dar įkeliamos (Ollama paleista, bet warmup nebaigtas)
                // Dažnai pasitaiko po pirmojo konteinerio paleidimo (modelių įkėlimas iš disko į GPU)
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
