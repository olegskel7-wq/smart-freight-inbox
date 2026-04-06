package com.smartfreight.inbox.actuator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

// @Component — Spring автоматически создаёт этот компонент
// HealthIndicator — Spring Actuator использует это для /actuator/health эндпоинта
// В Spring Boot 4 пакет сменился: org.springframework.boot.health.contributor (не actuator.health!)
@Component
public class OllamaHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(OllamaHealthIndicator.class);

    // URL Ollama (где запущен локальный AI)
    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    // Имена моделей AI, которые должны быть загружены
    @Value("${ollama.extraction-model:qwen2.5:7b}")
    private String extractionModel;

    @Value("${ollama.drafting-model:llama3.1:8b}")
    private String draftingModel;

    // RestTemplate — простой HTTP клиент для health check запросов
    // (WebClient здесь избыточен — health check простой синхронный запрос)
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public Health health() {
        try {
            // Запрашиваем список загруженных моделей у Ollama
            String tagsUrl = ollamaBaseUrl + "/api/tags";
            String response = restTemplate.getForObject(tagsUrl, String.class);

            // Проверяем: обе нужные модели присутствуют в ответе?
            if (response != null
                    && response.contains(extractionModel)
                    && response.contains(draftingModel)) {
                log.info("Ollama is healthy, both models loaded");
                return Health.up()
                        .withDetail("status", "Ready")
                        .withDetail("models", extractionModel + ", " + draftingModel)
                        .build();
            } else {
                // Модели ещё загружаются (Ollama запущена, но warmup не завершён)
                log.warn("Ollama models are not yet loaded, warming up...");
                return Health.outOfService()
                        .withDetail("status", "Warming up")
                        .withDetail("message", "Models are loading")
                        .build();
            }

        } catch (Exception e) {
            // Ollama не запущена или недоступна
            log.warn("Ollama health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("status", "Unavailable")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
