package com.smartfreight.inbox.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Container number extraction service using Qwen 2.5.
 *
 * Sends the email body to Ollama (POST /api/generate) and requests an ISO 6346 number.
 * The blocking {@code .block()} call is acceptable here: the invocation runs
 * in a servlet/IMAP worker thread, not the Netty event loop.
 *
 * If Ollama is unavailable or returns an unclear response,
 * a regex fallback is applied directly against the email text.
 */
@Service
public class AiExtractionService {

    private static final Logger log = LoggerFactory.getLogger(AiExtractionService.class);

    /**
     * ISO 6346 container number pattern: 4 uppercase letters + 7 digits.
     * E.g.: MSCU1234567, TCKU9876543.
     * Used as a fallback when the LLM returns an unrecognisable response.
     */
    private static final Pattern CONTAINER_PATTERN =
            Pattern.compile("\\b([A-Z]{4}\\d{7})\\b");

    /** Maximum email body length sent to the LLM — long emails are truncated to keep prompts fast. */
    private static final int MAX_PROMPT_BODY_CHARS = 2000;

    /** Ollama generate-call timeout — generous enough for slow LLM responses on CPU. */
    private static final Duration OLLAMA_TIMEOUT = Duration.ofSeconds(150);

    /**
     * Fallback prompt template used when the DB-backed {@code ai.extraction-prompt} row is
     * missing or blank. Kept in sync with migration V9. {@code {email}} is replaced with the
     * (truncated) email body at request time.
     */
    private static final String DEFAULT_EXTRACTION_PROMPT = """
            You are a logistics assistant. Extract the shipping container number from the email below.
            Container numbers follow ISO 6346 format: exactly 4 uppercase letters followed by 7 digits (e.g. MSCU1234567).
            Return ONLY the container number, nothing else. If no container number is found, return "NOT_FOUND".

            Email:
            {email}""";

    /** Extraction model name — read from application.yaml. */
    private final String extractionModel;

    private final WebClient ollamaWebClient;
    private final SettingsService settingsService;
    private final Counter fallbackCounter;
    private final Timer ollamaTimer;

    public AiExtractionService(WebClient ollamaWebClient,
                               SettingsService settingsService,
                               MeterRegistry meterRegistry,
                               @Value("${ollama.extraction-model:qwen2.5:7b}") String extractionModel) {
        this.ollamaWebClient = ollamaWebClient;
        this.settingsService = settingsService;
        this.extractionModel = extractionModel;
        this.fallbackCounter = Counter.builder("smartfreight_extraction_fallback_total")
                .description("Times regex fallback was used instead of LLM for container extraction")
                .register(meterRegistry);
        this.ollamaTimer = Timer.builder("smartfreight_ollama_request_seconds")
                .description("Ollama response time per model")
                .tag("model", "qwen2.5:7b")
                .tag("operation", "extraction")
                .register(meterRegistry);
    }

    /**
     * Sends the email body to Qwen 2.5 and retrieves a container number.
     *
     * Logic:
     * 1. A prompt is sent to Ollama (the LLM returns only the number)
     * 2. If the response exactly matches ISO 6346 — it is returned directly
     * 3. If the number is embedded in the response — it is extracted via regex
     * 4. If the LLM found nothing — regex is tried directly on the email text
     * 5. Any exception → regex fallback
     *
     * @param emailBody plain-text body of the email
     * @return container number (e.g. "MSCU1234567") or an empty string
     */
    public String extractContainerNumber(String emailBody) {
        if (emailBody == null || emailBody.isBlank()) {
            log.warn("Email body is empty, skipping extraction");
            return "";
        }

        String template = settingsService.getString(
                SettingsService.KEY_EXTRACTION_PROMPT, DEFAULT_EXTRACTION_PROMPT);
        String truncatedBody = emailBody.substring(0, Math.min(emailBody.length(), MAX_PROMPT_BODY_CHARS));
        String prompt = template.replace("{email}", truncatedBody);

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", extractionModel);
            requestBody.put("prompt", prompt);
            requestBody.put("stream", false);

            log.debug("Sending extraction request to Ollama model {}", extractionModel);

            long start = System.nanoTime();
            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaWebClient
                    .post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(OLLAMA_TIMEOUT);
            ollamaTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);

            if (response == null) {
                log.warn("Ollama returned null response during extraction");
                return fallbackRegexExtraction(emailBody);
            }

            String rawResponse = String.valueOf(response.getOrDefault("response", "")).trim();
            log.info("Qwen raw response: '{}'", rawResponse);

            if (CONTAINER_PATTERN.matcher(rawResponse).matches()) {
                log.info("Container number extracted by LLM: {}", rawResponse);
                return rawResponse;
            }

            Matcher m = CONTAINER_PATTERN.matcher(rawResponse.toUpperCase());
            if (m.find()) {
                log.info("Container number extracted from LLM response via regex: {}", m.group(1));
                return m.group(1);
            }

            log.warn("LLM could not extract container number, trying regex fallback");
            return fallbackRegexExtraction(emailBody);

        } catch (Exception e) {
            log.error("Extraction call to Ollama failed: {}", e.getMessage());
            return fallbackRegexExtraction(emailBody);
        }
    }

    /**
     * Fallback method: searches for a container number via regex directly in the email text.
     * Used when Ollama is unavailable or returned an unrecognisable response.
     *
     * @param body email body text
     * @return found container number or an empty string
     */
    private String fallbackRegexExtraction(String body) {
        fallbackCounter.increment();
        Matcher m = CONTAINER_PATTERN.matcher(body.toUpperCase());
        if (m.find()) {
            log.info("Container number found via regex fallback: {}", m.group(1));
            return m.group(1);
        }
        log.warn("No container number found in email body (LLM + regex both failed)");
        return "";
    }
}
