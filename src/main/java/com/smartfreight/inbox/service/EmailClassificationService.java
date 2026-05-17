package com.smartfreight.inbox.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Incoming email relevance classifier.
 *
 * Uses Qwen 2.5 (same model as AiExtractionService) to decide whether
 * an email is a freight/container inquiry or unrelated correspondence.
 *
 * Classification result:
 *   RELEVANT   - freight inquiry (status, location, ETA, customs, tracking, etc.)
 *   IRRELEVANT - other mail, ignored with no DB record
 *
 * Fail-open principle: if Ollama is unavailable the email is treated as RELEVANT
 * so that no real customer request is lost due to an AI outage.
 *
 * Execution context: called from EmailIngestionService before the first DB query,
 * so the HikariCP JDBC connection is not yet held during this LLM call.
 */
@Service
public class EmailClassificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailClassificationService.class);

    @Value("${ollama.extraction-model:qwen2.5:7b}")
    private String model;

    private final WebClient ollamaWebClient;
    private final Counter relevantCounter;
    private final Counter irrelevantCounter;

    public EmailClassificationService(@Qualifier("ollamaWebClient") WebClient ollamaWebClient,
                                      MeterRegistry meterRegistry) {
        this.ollamaWebClient = ollamaWebClient;
        this.relevantCounter = Counter.builder("smartfreight_emails_classified_total")
                .description("Emails classified by the AI filter")
                .tag("result", "relevant")
                .register(meterRegistry);
        this.irrelevantCounter = Counter.builder("smartfreight_emails_classified_total")
                .description("Emails classified by the AI filter")
                .tag("result", "irrelevant")
                .register(meterRegistry);
    }

    /**
     * Determines whether the email is a freight or shipping inquiry.
     *
     * Sends a short classification prompt to Ollama and expects a single-word reply.
     * Timeout: 30 s (vs 150 s for extraction) - one word generation is fast.
     *
     * Answer parsing:
     *   startsWith("IRRELEVANT")                     -> false
     *   startsWith("RELEVANT") and not "IRRELEVANT"  -> true
     *   any exception or unexpected answer            -> true (fail-open)
     *
     * @param subject email subject header
     * @param body    plain-text email body
     * @return true if this is a freight inquiry, false if unrelated
     */
    public boolean isFreightInquiry(String subject, String body) {
        if (body == null || body.isBlank()) {
            log.debug("Empty email body - treating as irrelevant");
            return false;
        }

        String truncatedBody = body.substring(0, Math.min(body.length(), 1000));
        String safeSubject = subject != null ? subject : "";

        String prompt = "You are a logistics email classifier.\n"
                + "Decide if this email is a freight or shipping inquiry "
                + "(container status, location, ETA, delivery, customs, tracking, shipment update, etc.).\n"
                + "Reply with exactly one word: RELEVANT or IRRELEVANT.\n\n"
                + "Subject: " + safeSubject + "\n"
                + "Email:\n" + truncatedBody;

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "prompt", prompt,
                    "stream", false
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaWebClient
                    .post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(30));

            if (response == null) {
                log.warn("Ollama returned null for classification - treating as relevant (fail-open)");
                return true;
            }

            String answer = String.valueOf(response.getOrDefault("response", "")).trim().toUpperCase();
            log.debug("Classification response: '{}'", answer);

            if (answer.startsWith("IRRELEVANT")) {
                log.info("Email classified as IRRELEVANT - will be ignored");
                irrelevantCounter.increment();
                return false;
            }

            if (answer.startsWith("RELEVANT")) {
                log.info("Email classified as RELEVANT - proceeding to pipeline");
                relevantCounter.increment();
                return true;
            }

            log.warn("Unexpected classification answer '{}' - treating as relevant (fail-open)", answer);
            relevantCounter.increment();
            return true;

        } catch (Exception e) {
            log.warn("Classification call failed: {} - treating as relevant (fail-open)", e.getMessage());
            relevantCounter.increment();
            return true;
        }
    }
}