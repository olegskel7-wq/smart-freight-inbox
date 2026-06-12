package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketType;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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
 * Incoming email relevance + type classifier.
 *
 * Uses Qwen 2.5 (same model as AiExtractionService) to decide whether an email is
 * a freight/shipping inquiry and, since Phase 1.4, which business type it is —
 * one LLM call answers both questions with a single word:
 *
 *   IRRELEVANT                       - other mail, parked as a terminal IRRELEVANT ticket
 *   RFQ / STATUS_UPDATE / BOOKING_CONFIRM / INVOICE_QUERY / DOCUMENT_REQUEST / GENERAL
 *                                    - relevant, with the type persisted on the ticket
 *
 * The typed prompt is gated by the {@code feature.ticket-type} flag (plan-gating seam,
 * same convention as attachment parsing / rate sheets). With the flag off the classifier
 * falls back to the pre-1.4 binary RELEVANT/IRRELEVANT prompt and assigns no type.
 *
 * Fail-open principle: if Ollama is unavailable or answers something unexpected, the
 * email is treated as RELEVANT (type GENERAL when typed) so that no real customer
 * request is lost due to an AI outage.
 *
 * Execution context (Phase 1.3 session 3): called from TicketProcessingService inside
 * the RabbitMQ consumer for tickets in RECEIVED status — never on the IMAP thread.
 */
@Service
public class EmailClassificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailClassificationService.class);

    /** Feature flag gating the typed (Phase 1.4) prompt; off = pre-1.4 binary classification. */
    public static final String FEATURE_KEY = "feature.ticket-type";

    /** Maximum email body length sent to the classifier — shorter than for extraction since one-word answer is faster. */
    private static final int MAX_CLASSIFICATION_BODY_CHARS = 1000;

    /** Classification call timeout — short because the response is a single word. */
    private static final Duration CLASSIFICATION_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Outcome of one classification call.
     *
     * @param relevant whether the email is a freight inquiry (false = park as IRRELEVANT)
     * @param type     business type; null when irrelevant or when {@code feature.ticket-type} is off
     */
    public record ClassificationResult(boolean relevant, TicketType type) {
        static final ClassificationResult IRRELEVANT = new ClassificationResult(false, null);
    }

    private final String model;

    private final WebClient ollamaWebClient;
    private final SettingsService settingsService;
    private final CircuitBreaker ollamaCircuitBreaker;
    private final MeterRegistry meterRegistry;
    private final Counter relevantCounter;
    private final Counter irrelevantCounter;

    public EmailClassificationService(@Qualifier("ollamaWebClient") WebClient ollamaWebClient,
                                      SettingsService settingsService,
                                      @Qualifier("ollamaCircuitBreaker") CircuitBreaker ollamaCircuitBreaker,
                                      MeterRegistry meterRegistry,
                                      @Value("${ollama.extraction-model:qwen2.5:7b}") String model) {
        this.ollamaWebClient = ollamaWebClient;
        this.settingsService = settingsService;
        this.ollamaCircuitBreaker = ollamaCircuitBreaker;
        this.meterRegistry = meterRegistry;
        this.model = model;
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
     * Classifies the email: relevance plus (when {@code feature.ticket-type} is on) business type.
     *
     * Sends a short classification prompt to Ollama and expects a single-word reply.
     * Timeout: 30 s (vs 150 s for extraction) - one word generation is fast.
     *
     * Answer parsing (see {@link #parseAnswer}): IRRELEVANT wins; otherwise the answer is
     * matched against the type names; anything unexpected — including a transport error —
     * is treated as relevant/GENERAL (fail-open).
     *
     * @param subject email subject header
     * @param body    plain-text email body (plus parsed attachment text)
     * @return relevance decision with an optional ticket type
     */
    public ClassificationResult classify(String subject, String body) {
        if (body == null || body.isBlank()) {
            log.debug("Empty email body - treating as irrelevant");
            return ClassificationResult.IRRELEVANT;
        }

        boolean typed = settingsService.isFeatureEnabled(FEATURE_KEY, true);

        String truncatedBody = body.substring(0, Math.min(body.length(), MAX_CLASSIFICATION_BODY_CHARS));
        String safeSubject = subject != null ? subject : "";

        String prompt = (typed ? typedPrompt() : binaryPrompt())
                + "\n\nSubject: " + safeSubject + "\n"
                + "Email:\n" + truncatedBody;

        String answer;
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "prompt", prompt,
                    "stream", false
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaCircuitBreaker.executeSupplier(() -> ollamaWebClient
                    .post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(CLASSIFICATION_TIMEOUT));

            if (response == null) {
                log.warn("Ollama returned null for classification - treating as relevant (fail-open)");
                return record(failOpen(typed));
            }
            answer = String.valueOf(response.getOrDefault("response", "")).trim().toUpperCase();
        } catch (CallNotPermittedException e) {
            // Breaker OPEN = Ollama is known to be down. Propagate so the pipeline parks the
            // ticket as RETRY_LATER (Phase 1.7) — a real classification verdict will be made
            // on retry (see TicketProcessingService: unclassified typed tickets re-enter step 0).
            log.warn("Ollama circuit breaker is open — classification call not attempted");
            throw e;
        } catch (Exception e) {
            log.warn("Classification call failed: {} - treating as relevant (fail-open)", e.getMessage());
            return record(failOpen(typed));
        }

        log.debug("Classification response: '{}'", answer);
        return record(parseAnswer(answer, typed));
    }

    /**
     * Whether typed classification (Phase 1.4) is active. In typed mode a persisted
     * {@code ticketType} is the durable marker that classification produced a verdict —
     * the pipeline uses this to re-run step 0 for tickets that were parked by an open
     * circuit breaker before classification could happen (Phase 1.7). Binary mode
     * persists nothing, so there the retry path keeps its historical skip.
     */
    public boolean isTypedMode() {
        return settingsService.isFeatureEnabled(FEATURE_KEY, true);
    }

    /**
     * Maps the model's one-word answer to a result.
     *
     * IRRELEVANT is checked first (it contains "RELEVANT" as a substring). In typed mode the
     * answer is matched against the {@link TicketType} names; a bare RELEVANT or anything
     * unrecognised falls back to GENERAL (fail-open). In binary mode the pre-1.4 contract
     * holds: relevant with no type.
     *
     * Package-private for unit testing.
     */
    static ClassificationResult parseAnswer(String answer, boolean typed) {
        if (answer.startsWith("IRRELEVANT")) {
            return ClassificationResult.IRRELEVANT;
        }
        if (!typed) {
            if (!answer.startsWith("RELEVANT")) {
                log.warn("Unexpected classification answer '{}' - treating as relevant (fail-open)", answer);
            }
            return new ClassificationResult(true, null);
        }
        for (TicketType type : TicketType.values()) {
            if (answer.startsWith(type.name())) {
                return new ClassificationResult(true, type);
            }
        }
        if (!answer.startsWith("RELEVANT")) {
            log.warn("Unexpected classification answer '{}' - treating as relevant/GENERAL (fail-open)", answer);
        }
        return failOpen(true);
    }

    private static ClassificationResult failOpen(boolean typed) {
        return new ClassificationResult(true, typed ? TicketType.GENERAL : null);
    }

    /** Updates metrics and logs the outcome; returns the result unchanged. */
    private ClassificationResult record(ClassificationResult result) {
        if (!result.relevant()) {
            log.info("Email classified as IRRELEVANT - pipeline will stop");
            irrelevantCounter.increment();
            return result;
        }
        relevantCounter.increment();
        if (result.type() != null) {
            log.info("Email classified as {} - proceeding to pipeline", result.type());
            meterRegistry.counter("smartfreight_tickets_type_total", "type", result.type().name()).increment();
        } else {
            log.info("Email classified as RELEVANT - proceeding to pipeline");
        }
        return result;
    }

    /** Phase 1.4 prompt: one word — IRRELEVANT or a business type. */
    private static String typedPrompt() {
        return "You are a logistics email classifier.\n"
                + "If this email is NOT related to freight, shipping or logistics, reply IRRELEVANT.\n"
                + "Otherwise classify it into exactly one category:\n"
                + "RFQ - request for a price, rate or quotation\n"
                + "STATUS_UPDATE - asking where a shipment/container is, ETA, tracking\n"
                + "BOOKING_CONFIRM - booking request or booking confirmation\n"
                + "INVOICE_QUERY - question about an invoice, charges or payment\n"
                + "DOCUMENT_REQUEST - asking for documents (POD, bill of lading, customs papers)\n"
                + "GENERAL - any other freight-related email\n"
                + "Reply with exactly one word: IRRELEVANT, RFQ, STATUS_UPDATE, BOOKING_CONFIRM, "
                + "INVOICE_QUERY, DOCUMENT_REQUEST or GENERAL.";
    }

    /** Pre-1.4 binary prompt, used when feature.ticket-type is off. */
    private static String binaryPrompt() {
        return "You are a logistics email classifier.\n"
                + "Decide if this email is a freight or shipping inquiry "
                + "(container status, location, ETA, delivery, customs, tracking, shipment update, etc.).\n"
                + "Reply with exactly one word: RELEVANT or IRRELEVANT.";
    }
}
