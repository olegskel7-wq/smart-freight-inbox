package com.smartfreight.inbox.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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
import java.util.regex.PatternSyntaxException;

/**
 * Identifier extraction service using Qwen 2.5.
 *
 * Sends the email body to Ollama (POST /api/generate) and requests the key business
 * identifier (an ISO 6346 container number in the freight reference vertical). The
 * blocking {@code .block()} call is acceptable here: the invocation runs in a
 * servlet/IMAP worker thread, not the Netty event loop.
 *
 * If Ollama is unavailable or returns an unclear response, a regex fallback is applied
 * directly against the email text. The fallback pattern is overridable per tenant
 * ({@code ai.extraction-fallback-regex}), so a different vertical can match its own
 * identifier format without a code change.
 */
@Service
public class AiExtractionService {

    private static final Logger log = LoggerFactory.getLogger(AiExtractionService.class);

    /**
     * Default fallback identifier pattern: ISO 6346 container numbers — 4 uppercase letters
     * + 7 digits (e.g. MSCU1234567, TCKU9876543), the freight reference vertical. Used when
     * the LLM returns an unrecognisable response and no tenant override is configured.
     */
    static final String DEFAULT_IDENTIFIER_REGEX = "\\b([A-Z]{4}\\d{7})\\b";
    private static final Pattern DEFAULT_IDENTIFIER_PATTERN = Pattern.compile(DEFAULT_IDENTIFIER_REGEX);

    /**
     * Settings key for the overridable fallback identifier regex. Resolved per call through
     * SettingsService (so it honours the per-tenant override chain); the code default above
     * applies when the row is absent — no migration required.
     */
    static final String KEY_FALLBACK_REGEX = "ai.extraction-fallback-regex";

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

    private final OllamaClientProvider ollamaClients;
    private final SettingsService settingsService;
    private final Counter fallbackCounter;
    private final Timer ollamaTimer;

    public AiExtractionService(OllamaClientProvider ollamaClients,
                               SettingsService settingsService,
                               MeterRegistry meterRegistry,
                               @Value("${ollama.extraction-model:qwen2.5:7b}") String extractionModel) {
        this.ollamaClients = ollamaClients;
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
     * Sends the email body to Qwen 2.5 and retrieves the key identifier.
     *
     * Logic:
     * 1. A prompt is sent to Ollama (the LLM returns only the identifier)
     * 2. If the response exactly matches the identifier pattern — it is returned directly
     * 3. If the identifier is embedded in the response — it is extracted via regex
     * 4. If the LLM found nothing — regex is tried directly on the email text
     * 5. Any exception → regex fallback
     *
     * @param emailBody plain-text body of the email
     * @return extracted identifier (e.g. "MSCU1234567") or an empty string
     */
    public String extractIdentifier(String emailBody) {
        if (emailBody == null || emailBody.isBlank()) {
            log.warn("Email body is empty, skipping extraction");
            return "";
        }

        Pattern identifierPattern = resolveIdentifierPattern();

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
            // Phase 2.2 S3: client + breaker resolved per call for the CURRENT tenant's endpoint.
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

            if (response == null) {
                log.warn("Ollama returned null response during extraction");
                return fallbackRegexExtraction(emailBody, identifierPattern);
            }

            String rawResponse = String.valueOf(response.getOrDefault("response", "")).trim();
            log.info("Qwen raw response: '{}'", rawResponse);

            if (identifierPattern.matcher(rawResponse).matches()) {
                log.info("Identifier extracted by LLM: {}", rawResponse);
                return rawResponse;
            }

            Matcher m = identifierPattern.matcher(rawResponse.toUpperCase());
            if (m.find()) {
                log.info("Identifier extracted from LLM response via regex: {}", m.group(1));
                return m.group(1);
            }

            log.warn("LLM could not extract identifier, trying regex fallback");
            return fallbackRegexExtraction(emailBody, identifierPattern);

        } catch (CallNotPermittedException e) {
            // Breaker OPEN = Ollama is known to be down. Propagate so the pipeline parks the
            // ticket as RETRY_LATER (Phase 1.7) instead of producing a degraded regex result.
            log.warn("Ollama circuit breaker is open — extraction call not attempted");
            throw e;
        } catch (Exception e) {
            log.error("Extraction call to Ollama failed: {}", e.getMessage());
            return fallbackRegexExtraction(emailBody, identifierPattern);
        }
    }

    /**
     * Resolves the fallback identifier pattern for the current tenant. Returns the ISO 6346
     * default when no override is set, or when the configured regex is blank or fails to
     * compile — a bad setting must degrade to the reference pattern, never break extraction.
     */
    Pattern resolveIdentifierPattern() {
        String regex = settingsService.getString(KEY_FALLBACK_REGEX, DEFAULT_IDENTIFIER_REGEX);
        if (regex == null || regex.isBlank()) {
            return DEFAULT_IDENTIFIER_PATTERN;
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            log.warn("Malformed {} '{}' — falling back to the default ISO 6346 pattern",
                    KEY_FALLBACK_REGEX, regex);
            return DEFAULT_IDENTIFIER_PATTERN;
        }
    }

    /**
     * Fallback method: searches for an identifier via regex directly in the email text.
     * Used when Ollama is unavailable or returned an unrecognisable response.
     *
     * @param body    email body text
     * @param pattern the identifier pattern resolved for the current tenant
     * @return found identifier or an empty string
     */
    private String fallbackRegexExtraction(String body, Pattern pattern) {
        fallbackCounter.increment();
        Matcher m = pattern.matcher(body.toUpperCase());
        if (m.find()) {
            log.info("Identifier found via regex fallback: {}", m.group(1));
            return m.group(1);
        }
        log.warn("No identifier found in email body (LLM + regex both failed)");
        return "";
    }
}
