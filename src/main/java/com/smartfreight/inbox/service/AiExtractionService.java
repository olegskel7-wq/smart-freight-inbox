package com.smartfreight.inbox.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Сервис извлечения номера контейнера из текста письма с помощью Qwen 2.5
// Использует локальный Ollama (POST /api/generate) — синхронный вызов через .block()
// Допускается блокирующий .block() потому что метод вызывается из @Async потока (не Netty event loop)
@Service
public class AiExtractionService {

    private static final Logger log = LoggerFactory.getLogger(AiExtractionService.class);

    // Шаблон ISO 6346: 4 заглавные буквы + 7 цифр (например MSCU1234567)
    // Используется как резервный способ извлечения, если LLM вернул мусор
    private static final Pattern CONTAINER_PATTERN =
            Pattern.compile("\\b([A-Z]{4}\\d{7})\\b");

    // Имя модели для извлечения — читается из application.yaml
    @Value("${ollama.extraction-model:qwen2.5:7b}")
    private String extractionModel;

    // WebClient, настроенный в WebClientConfig (таймауты, пул соединений)
    private final WebClient ollamaWebClient;

    public AiExtractionService(WebClient ollamaWebClient) {
        this.ollamaWebClient = ollamaWebClient;
    }

    // Отправляем тело письма в Qwen 2.5 и просим вернуть номер контейнера.
    // Возвращает найденный номер (например "MSCU1234567") или пустую строку если не найдено.
    public String extractContainerNumber(String emailBody) {
        if (emailBody == null || emailBody.isBlank()) {
            log.warn("Email body is empty, skipping extraction");
            return "";
        }

        // Промпт для Qwen: просим вернуть ТОЛЬКО номер, никакого лишнего текста
        String prompt = """
                You are a logistics assistant. Extract the shipping container number from the email below.
                Container numbers follow ISO 6346 format: exactly 4 uppercase letters followed by 7 digits (e.g. MSCU1234567).
                Return ONLY the container number, nothing else. If no container number is found, return "NOT_FOUND".

                Email:
                """ + emailBody.substring(0, Math.min(emailBody.length(), 2000));

        try {
            // Собираем тело запроса к Ollama API
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", extractionModel);
            requestBody.put("prompt", prompt);
            requestBody.put("stream", false); // одним ответом, не потоком

            log.debug("Sending extraction request to Ollama model {}", extractionModel);

            // Отправляем POST /api/generate и ждём ответа (блокирующий вызов)
            // Таймаут 150 сек задан в WebClientConfig через ReadTimeoutHandler
            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaWebClient
                    .post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(150));

            if (response == null) {
                log.warn("Ollama returned null response during extraction");
                return fallbackRegexExtraction(emailBody);
            }

            // Ollama возвращает JSON: {"response": "MSCU1234567", "done": true, ...}
            String rawResponse = String.valueOf(response.getOrDefault("response", "")).trim();
            log.info("Qwen raw response: '{}'", rawResponse);

            // Если LLM вернул чистый номер — проверяем формат
            if (CONTAINER_PATTERN.matcher(rawResponse).matches()) {
                log.info("Container number extracted by LLM: {}", rawResponse);
                return rawResponse;
            }

            // LLM мог вернуть номер внутри предложения — пробуем найти паттерн в ответе
            Matcher m = CONTAINER_PATTERN.matcher(rawResponse.toUpperCase());
            if (m.find()) {
                log.info("Container number extracted from LLM response via regex: {}", m.group(1));
                return m.group(1);
            }

            // LLM не нашёл номер — пробуем прямой regex по тексту письма
            log.warn("LLM could not extract container number, trying regex fallback");
            return fallbackRegexExtraction(emailBody);

        } catch (Exception e) {
            log.error("Extraction call to Ollama failed: {}", e.getMessage());
            return fallbackRegexExtraction(emailBody);
        }
    }

    // Резервный метод: ищем номер контейнера регулярным выражением прямо в тексте письма.
    // Срабатывает, если Ollama недоступен или вернул нечитаемый ответ.
    private String fallbackRegexExtraction(String body) {
        Matcher m = CONTAINER_PATTERN.matcher(body.toUpperCase());
        if (m.find()) {
            log.info("Container number found via regex fallback: {}", m.group(1));
            return m.group(1);
        }
        log.warn("No container number found in email body (LLM + regex both failed)");
        return "";
    }
}
