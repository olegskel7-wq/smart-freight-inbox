package com.smartfreight.inbox.actuator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Health Indicator для Ollama AI сервера.
 * 
 * Используется Spring Boot Actuator эндпоинтом /actuator/health для проверки
 * доступности критических зависимостей приложения.
 * 
 * Статусы:
 * - UP: Ollama работает и обе модели (Qwen + Llama) загружены
 * - OUT_OF_SERVICE: Ollama доступна, но модели ещё загружаются (warmup)
 * - DOWN: Ollama не доступна или произошла ошибка сети
 * 
 * Используется для:
 * - Мониторинга в Grafana/Prometheus
 * - Readiness probe в Kubernetes
 * - LB для маршрутизации трафика только на здоровые инстансы
 */
@Component
public class OllamaHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(OllamaHealthIndicator.class);

    // URL Ollama сервера (обычно http://localhost:11434 в разработке)
    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    // Имя модели для извлечения контейнеров (Qwen 2.5)
    @Value("${ollama.extraction-model:qwen2.5:7b}")
    private String extractionModel;

    // Имя модели для генерации ответов (Llama 3.1)
    @Value("${ollama.drafting-model:llama3.1:8b}")
    private String draftingModel;

    // RestTemplate — простой синхронный HTTP клиент
    // Используется для health check, так как это быстрая операция (не требуется async)
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Проверяет здоровье Ollama сервера.
     * 
     * Алгоритм:
     * 1. GET /api/tags у Ollama (возвращает список загруженных моделей)
     * 2. Проверяем что в ответе присутствуют оба имена моделей (extractionModel, draftingModel)
     * 3. Если обе найдены → UP (полностью готово)
     * 4. Если хотя бы одна не найдена → OUT_OF_SERVICE (модели ещё загружаются)
     * 5. Если Ollama не ответила или ошибка сети → DOWN
     * 
     * @return Health объект с статусом и дополнительной информацией
     */
    @Override
    public Health health() {
        try {
            // Запрашиваем список загруженных моделей у Ollama
            String tagsUrl = ollamaBaseUrl + "/api/tags";
            String response = restTemplate.getForObject(tagsUrl, String.class);

            // Проверяем: обе модели присутствуют?
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
                // Обычно это происходит при первом запуске контейнера (загрузка моделей из disk → GPU memory)
                log.warn("Ollama models are not yet loaded, warming up...");
                return Health.outOfService()
                        .withDetail("status", "Warming up")
                        .withDetail("message", "Models are loading")
                        .build();
            }

        } catch (Exception e) {
            // Ollama не запущена, не доступна в сети, или произошла другая ошибка
            log.warn("Ollama health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("status", "Unavailable")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
