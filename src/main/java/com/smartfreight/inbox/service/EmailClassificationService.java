package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.entity.enums.Urgency;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * Feature flag gating the Phase 3.10 smart-routing extension (urgency + sentiment in the same call).
     * Default ON. It only adds signals on top of the typed verdict — when {@link #FEATURE_KEY} is off
     * (binary mode) there is no type to ride along, so urgency/sentiment are not requested either.
     */
    public static final String FEATURE_SMART_ROUTING = "feature.smart-routing";

    /** Maximum email body length sent to the classifier — shorter than for extraction since one-word answer is faster. */
    private static final int MAX_CLASSIFICATION_BODY_CHARS = 1000;

    /** Classification call timeout — short because the response is a single word. */
    private static final Duration CLASSIFICATION_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Outcome of one classification call.
     *
     * @param relevant          whether the email is a freight inquiry (false = park as IRRELEVANT)
     * @param type              business type; null when irrelevant or when {@code feature.ticket-type} is off
     * @param urgency           how time-critical the email is (Phase 3.10); NORMAL when smart routing is off
     * @param sentimentNegative whether the customer's tone reads negative (Phase 3.10); false when off
     */
    public record ClassificationResult(boolean relevant, TicketType type,
                                       Urgency urgency, boolean sentimentNegative) {

        /** Relevance + type with no routing signals (NORMAL/calm) — retry path, binary mode, fail-open. */
        public ClassificationResult(boolean relevant, TicketType type) {
            this(relevant, type, Urgency.NORMAL, false);
        }

        /**
         * The single definition of "escalated" (Phase 3.10): HIGH urgency or a negative tone. Drives the
         * tighter SLA window and the escalation auto-assign — see {@link SlaService#deadlineFor} and
         * {@link TicketProcessingService}. (The escalated-first list sort re-encodes this as column order,
         * and the metric counts the two reasons separately, so those two stay self-contained by design.)
         */
        public boolean isEscalated() {
            return urgency == Urgency.HIGH || sentimentNegative;
        }

        static final ClassificationResult IRRELEVANT = new ClassificationResult(false, null);
    }

    private final String model;

    private final OllamaClientProvider ollamaClients;
    private final SettingsService settingsService;
    private final MeterRegistry meterRegistry;
    private final Counter relevantCounter;
    private final Counter irrelevantCounter;

    public EmailClassificationService(OllamaClientProvider ollamaClients,
                                      SettingsService settingsService,
                                      MeterRegistry meterRegistry,
                                      @Value("${ollama.extraction-model:qwen2.5:7b}") String model) {
        this.ollamaClients = ollamaClients;
        this.settingsService = settingsService;
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
        // Smart routing (Phase 3.10) rides on the typed verdict — no type, nothing to route.
        boolean smartRouting = typed && settingsService.isFeatureEnabled(FEATURE_SMART_ROUTING, true);

        String truncatedBody = body.substring(0, Math.min(body.length(), MAX_CLASSIFICATION_BODY_CHARS));
        String safeSubject = subject != null ? subject : "";

        String instruction = typed ? (smartRouting ? typedRoutingPrompt() : typedPrompt()) : binaryPrompt();
        String prompt = instruction
                + "\n\nSubject: " + safeSubject + "\n"
                + "Email:\n" + truncatedBody;

        String answer;
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "prompt", prompt,
                    "stream", false
            );

            // Phase 2.2 S3: client + breaker resolved per call for the CURRENT tenant's endpoint.
            WebClient ollamaWebClient = ollamaClients.client();
            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaClients.circuitBreaker().executeSupplier(() -> ollamaWebClient
                    .post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(CLASSIFICATION_TIMEOUT));
            ollamaClients.recordUsage("classification", model, response);

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
        return record(parseAnswer(answer, typed, smartRouting));
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

    /** Backwards-compatible parse with no smart-routing signals (relevance + type only). */
    static ClassificationResult parseAnswer(String answer, boolean typed) {
        return parseAnswer(answer, typed, false);
    }

    /**
     * Maps the model's answer to a result.
     *
     * In smart-routing mode (typed + {@code feature.smart-routing}) the model replies
     * {@code TYPE|URGENCY|SENTIMENT} (e.g. {@code RFQ|HIGH|NEGATIVE}); the answer is split on '|' and
     * each field parsed independently and leniently. Otherwise only the first field (the type / RELEVANT
     * / IRRELEVANT word) matters and the routing fields stay at their fail-open defaults (NORMAL/calm).
     *
     * IRRELEVANT is checked first (it contains "RELEVANT" as a substring) and short-circuits — spam
     * carries no urgency/sentiment. In typed mode the type field is matched against the {@link TicketType}
     * names; a bare RELEVANT or anything unrecognised falls back to GENERAL (fail-open). In binary mode
     * the pre-1.4 contract holds: relevant with no type.
     *
     * Package-private for unit testing.
     */
    static ClassificationResult parseAnswer(String answer, boolean typed, boolean smartRouting) {
        String[] fields = answer.split("\\|");   // split always yields length >= 1
        String typeField = fields[0].trim();

        if (typeField.startsWith("IRRELEVANT")) {
            return ClassificationResult.IRRELEVANT;
        }
        if (!typed) {
            if (!typeField.startsWith("RELEVANT")) {
                log.warn("Unexpected classification answer '{}' - treating as relevant (fail-open)", answer);
            }
            return new ClassificationResult(true, null);
        }

        TicketType type = firstStartingWith(typeField, TicketType.values(), null);
        if (type == null) {
            if (!typeField.startsWith("RELEVANT")) {
                log.warn("Unexpected classification answer '{}' - treating as relevant/GENERAL (fail-open)", answer);
            }
            type = TicketType.GENERAL;
        }

        if (!smartRouting) {
            return new ClassificationResult(true, type);
        }
        // Lenient: unknown/blank urgency fails open to NORMAL (no spurious escalation); negative only on an
        // explicit NEGATIVE token (POSITIVE/NEUTRAL/blank/noise = calm).
        Urgency urgency = firstStartingWith(fields.length > 1 ? fields[1] : "", Urgency.values(), Urgency.NORMAL);
        boolean negative = (fields.length > 2 ? fields[2] : "").trim().startsWith("NEGATIVE");
        return new ClassificationResult(true, type, urgency, negative);
    }

    /** First enum constant whose name the (trimmed) field starts with, else {@code fallback} — the shared lenient parse. */
    private static <E extends Enum<E>> E firstStartingWith(String field, E[] values, E fallback) {
        String value = field.trim();
        for (E candidate : values) {
            if (value.startsWith(candidate.name())) {
                return candidate;
            }
        }
        return fallback;
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
        // Phase 3.10: count escalations (HIGH urgency and/or negative tone) for routing observability.
        // Like smartfreight_tickets_type_total above, this counts classification VERDICTS, not unique
        // tickets — a ticket whose pipeline rolls back after classification and re-classifies on retry
        // is counted again. That's acceptable for an observability gauge (and consistent with the type
        // metric); de-duplicating would need per-ticket idempotency the classifier doesn't carry.
        if (result.urgency() == Urgency.HIGH) {
            meterRegistry.counter("smartfreight_tickets_escalated_total", "reason", "urgency").increment();
        }
        if (result.sentimentNegative()) {
            meterRegistry.counter("smartfreight_tickets_escalated_total", "reason", "sentiment").increment();
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

    /**
     * Phase 3.10 prompt: the typed verdict plus urgency and sentiment in ONE answer, pipe-separated —
     * {@code TYPE|URGENCY|SENTIMENT}. Kept terse and example-anchored so a local 7B stays on format;
     * the parser is lenient and fail-open, so drift degrades to NORMAL/calm rather than an error.
     */
    private static String typedRoutingPrompt() {
        return "You are a logistics email triage assistant.\n"
                + "If this email is NOT related to freight, shipping or logistics, reply exactly: IRRELEVANT\n"
                + "Otherwise reply with THREE fields separated by '|' : TYPE|URGENCY|SENTIMENT\n"
                + "TYPE is one of: RFQ (price/quote), STATUS_UPDATE (where is it / ETA / tracking), "
                + "BOOKING_CONFIRM (booking request or confirmation), INVOICE_QUERY (invoice/charges/payment), "
                + "DOCUMENT_REQUEST (POD, bill of lading, customs papers), GENERAL (any other freight email).\n"
                + "URGENCY is one of: LOW (no time pressure), NORMAL (standard), "
                + "HIGH (time-critical: deadline, demurrage, stuck/held shipment, 'urgent', 'asap').\n"
                + "SENTIMENT is one of: NEUTRAL, POSITIVE, NEGATIVE (angry, frustrated, complaining, threatening to leave).\n"
                + "Examples: RFQ|NORMAL|NEUTRAL   STATUS_UPDATE|HIGH|NEGATIVE\n"
                + "Reply with ONLY that one line, no explanation.";
    }

    /** Pre-1.4 binary prompt, used when feature.ticket-type is off. */
    private static String binaryPrompt() {
        return "You are a logistics email classifier.\n"
                + "Decide if this email is a freight or shipping inquiry "
                + "(container status, location, ETA, delivery, customs, tracking, shipment update, etc.).\n"
                + "Reply with exactly one word: RELEVANT or IRRELEVANT.";
    }
}
