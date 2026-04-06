package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

// Сервис генерации черновика ответа с помощью Llama 3.1
// Получает тело письма + данные контейнера → возвращает готовый черновик для оператора
@Service
public class AiDraftingService {

    private static final Logger log = LoggerFactory.getLogger(AiDraftingService.class);

    // Формат для вывода ETA (например: "15 Apr 2025, 14:30")
    private static final DateTimeFormatter ETA_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    // Имя модели для генерации черновика — читается из application.yaml
    @Value("${ollama.drafting-model:llama3.1:8b}")
    private String draftingModel;

    private final WebClient ollamaWebClient;

    public AiDraftingService(WebClient ollamaWebClient) {
        this.ollamaWebClient = ollamaWebClient;
    }

    // Генерирует черновик ответа на письмо клиента.
    // Параметры:
    //   emailBody  — оригинальный текст письма (запрос клиента)
    //   container  — данные найденного контейнера (может быть null если не найден)
    // Возвращает строку с черновиком ответа.
    public String generateDraft(String emailBody, Container container) {
        // Формируем контекстный блок с данными контейнера
        String containerContext = buildContainerContext(container);

        // Промпт для Llama: просим профессиональный, краткий ответ на письмо
        String prompt = """
                You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
                Write a concise, professional email reply to the customer's inquiry below.
                Use the container information provided. Be helpful, polite, and factual.
                Do NOT make up information that is not in the container data.
                Reply in the same language as the customer's email.

                Container information:
                """ + containerContext + """

                Customer's email:
                """ + emailBody.substring(0, Math.min(emailBody.length(), 2000)) + """

                Write only the email reply body (no subject line, no "Dear..." header unless natural).
                """;

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", draftingModel);
            requestBody.put("prompt", prompt);
            requestBody.put("stream", false);

            log.debug("Sending drafting request to Ollama model {}", draftingModel);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaWebClient
                    .post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(150));

            if (response == null) {
                log.warn("Ollama returned null response during drafting");
                return buildFallbackDraft(container);
            }

            String draft = String.valueOf(response.getOrDefault("response", "")).trim();

            if (draft.isEmpty()) {
                log.warn("Ollama returned empty draft");
                return buildFallbackDraft(container);
            }

            log.info("Draft generated successfully ({} chars)", draft.length());
            return draft;

        } catch (Exception e) {
            log.error("Drafting call to Ollama failed: {}", e.getMessage());
            return buildFallbackDraft(container);
        }
    }

    // Формирует текстовый блок с данными контейнера для вставки в промпт
    private String buildContainerContext(Container container) {
        if (container == null) {
            return "Container not found in our system.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Container number: ").append(container.getContainerNumber()).append("\n");
        sb.append("Status: ").append(container.getStatus() != null ? container.getStatus() : "Unknown").append("\n");

        if (container.getEta() != null) {
            sb.append("ETA: ").append(container.getEta().format(ETA_FORMATTER)).append("\n");
        } else {
            sb.append("ETA: Not available\n");
        }

        sb.append("Location: ").append(container.getLocation() != null ? container.getLocation() : "Unknown");
        return sb.toString();
    }

    // Резервный черновик — возвращается если Ollama недоступен.
    // Содержит базовую информацию из БД без AI-генерации.
    private String buildFallbackDraft(Container container) {
        if (container == null) {
            return """
                    Thank you for your enquiry.
                    Unfortunately, we were unable to locate the container in our system at this time.
                    Please contact our operations team for further assistance.
                    Kind regards,
                    Freight Operations Team
                    """;
        }

        return String.format("""
                Thank you for your enquiry regarding container %s.
                
                Current status: %s
                ETA: %s
                Location: %s
                
                Please do not hesitate to contact us if you require further information.
                Kind regards,
                Freight Operations Team
                """,
                container.getContainerNumber(),
                container.getStatus() != null ? container.getStatus() : "N/A",
                container.getEta() != null ? container.getEta().format(ETA_FORMATTER) : "N/A",
                container.getLocation() != null ? container.getLocation() : "N/A"
        );
    }
}
