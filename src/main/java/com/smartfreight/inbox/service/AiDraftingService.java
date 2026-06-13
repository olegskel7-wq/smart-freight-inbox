package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TrackedItem;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reply draft generation service using Llama 3.1.
 *
 * Receives the email body and container data →
 * returns a ready-to-review draft for the operator.
 *
 * If Ollama is unavailable or returns an empty response,
 * a static fallback draft is used with data from the DB.
 */
@Service
public class AiDraftingService {

    private static final Logger log = LoggerFactory.getLogger(AiDraftingService.class);

    /** ETA date formatter for display (e.g. "15 Apr 2025, 14:30"). English locale — month names
     * must not follow the server's default locale, drafts are quoted to UK customers. */
    private static final DateTimeFormatter ETA_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", java.util.Locale.ENGLISH);

    /** Maximum email body length sent to the LLM. */
    private static final int MAX_PROMPT_BODY_CHARS = 2000;

    /** Ollama generate-call timeout. */
    private static final Duration OLLAMA_TIMEOUT = Duration.ofSeconds(150);

    /**
     * Fallback prompt template used when the DB-backed {@code ai.drafting-prompt} row is missing
     * or blank. Kept in sync with migration V9. At request time {@code {itemContext}} is replaced
     * with the looked-up item data and {@code {email}} with the (truncated) email body.
     */
    private static final String DEFAULT_DRAFTING_PROMPT = """
            You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
            Write a concise, professional email reply to the customer's inquiry below.
            Use the container information provided. Be helpful, polite, and factual.
            Do NOT make up information that is not in the container data.
            Reply in the same language as the customer's email.

            Container information:
            {itemContext}

            Rate information (prices already include our margin — quote them as-is, never mention margin):
            {rateContext}

            Customer's email:
            {email}

            Write only the email reply body (no subject line, no "Dear..." header unless natural).""";

    /** Substituted for {@code {rateContext}} when no rates matched the enquiry. */
    private static final String NO_RATES_NOTE = "No rate information available for this enquiry. Do not quote any price.";

    /**
     * Fallback prompt for proactive status updates (Phase 2.4) when the DB-backed
     * {@code ai.status-update-prompt} row is missing or blank. Kept in sync with migration V22.
     * {@code {itemContext}} → the looked-up shipment data, {@code {previousStatus}} → the status
     * the customer was last told about.
     */
    private static final String DEFAULT_STATUS_UPDATE_PROMPT = """
            You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
            Write a short, proactive status update email to notify the customer that their shipment has progressed.
            This is NOT a reply to a customer message — the customer has not just written to you. Open accordingly.
            Use only the shipment information provided. Be helpful, polite and factual. Do NOT invent information.

            Shipment information:
            {itemContext}

            Previous status: {previousStatus}

            Write only the email body (no subject line). Keep it to a few sentences.""";

    /**
     * Fallback prompt for proactive quote follow-ups (Phase 2.5) when the DB-backed
     * {@code ai.quote-followup-prompt} row is missing or blank. Kept in sync with migration V24.
     * {@code {originalEnquiry}} → the customer's original RFQ text.
     */
    private static final String DEFAULT_QUOTE_FOLLOWUP_PROMPT = """
            You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
            You sent this customer a freight quote a couple of days ago and have not heard back.
            Write a short, polite follow-up email gently checking whether they have any questions about the quote and whether they would like to proceed.
            This is NOT a reply to a new customer message — you are reaching out proactively. Open accordingly.
            Do NOT restate prices or invent any new figures. Keep it warm, brief and low-pressure.

            The customer's original enquiry was:
            {originalEnquiry}

            Write only the email body (no subject line). Keep it to a few sentences.""";

    /**
     * Static fallback nudge used when Ollama is unavailable for a quote follow-up — there is no
     * {@link TrackedItem} here (a follow-up is about a quote, not a shipment), so the generic
     * shipment fallback does not apply.
     */
    private static final String DEFAULT_FOLLOWUP_FALLBACK = """
            Dear Customer,

            I hope you are well. I am following up on the freight quote we sent you recently.
            Please let me know if you have any questions or if you would like to go ahead — we would be glad to help.

            Kind regards,
            Customer Service Team
            """;

    /** Draft generation model name — read from application.yaml. */
    private final String draftingModel;

    private final OllamaClientProvider ollamaClients;
    private final SettingsService settingsService;
    private final Timer ollamaTimer;

    public AiDraftingService(OllamaClientProvider ollamaClients,
                             SettingsService settingsService,
                             MeterRegistry meterRegistry,
                             @Value("${ollama.drafting-model:llama3.1:8b}") String draftingModel) {
        this.ollamaClients = ollamaClients;
        this.settingsService = settingsService;
        this.draftingModel = draftingModel;
        this.ollamaTimer = Timer.builder("smartfreight_ollama_request_seconds")
                .description("Ollama response time per model")
                .tag("model", "llama3.1:8b")
                .tag("operation", "drafting")
                .register(meterRegistry);
    }

    /**
     * Generates a professional reply draft for a customer email.
     *
     * Executes POST /api/generate to Ollama with a prompt containing:
     * — the email text (up to 2000 characters)
     * — item data (if found)
     *
     * The model is instructed to reply in the same language as the customer
     * and not to use information that is not present in the item data.
     *
     * @param emailBody  customer email text
     * @param item       tracked-item data (may be null if not found)
     * @return generated or fallback draft
     */
    public String generateDraft(String emailBody, TrackedItem item) {
        return generateDraft(emailBody, item, "");
    }

    /**
     * Generates a reply draft with carrier rate context (Phase 1.2).
     *
     * <p>Rate placement is defensive about prompt history: the {@code ai.drafting-prompt} row
     * seeded by V9 predates {@code {rateContext}}, so when the stored template lacks the
     * placeholder but rates matched, the rate block is appended to the prompt instead of being
     * silently dropped.
     *
     * @param emailBody   customer email text (body + parsed attachments)
     * @param item        tracked-item data (may be null if not found)
     * @param rateContext matched rate lines from {@link RateSheetService}, or empty
     * @return generated or fallback draft
     */
    public String generateDraft(String emailBody, TrackedItem item, String rateContext) {
        String itemContext = buildItemContext(item);
        String truncatedBody = emailBody.substring(0, Math.min(emailBody.length(), MAX_PROMPT_BODY_CHARS));
        String rates = rateContext == null ? "" : rateContext.trim();

        String template = settingsService.getString(
                SettingsService.KEY_DRAFTING_PROMPT, DEFAULT_DRAFTING_PROMPT);
        String prompt = template
                .replace("{itemContext}", itemContext)
                .replace("{email}", truncatedBody);

        if (template.contains("{rateContext}")) {
            prompt = prompt.replace("{rateContext}", rates.isEmpty() ? NO_RATES_NOTE : rates);
        } else if (!rates.isEmpty()) {
            prompt = prompt + "\n\nRate information (prices already include our margin — quote them as-is, "
                    + "never mention margin):\n" + rates;
        }

        return callOllama(prompt, buildFallbackDraft(item));
    }

    /**
     * Generates a proactive status-update notification (Phase 2.4).
     *
     * <p>Unlike {@link #generateDraft}, this is not a reply to an inbound email — it is composed
     * when a watched shipment reaches a milestone, so it uses the dedicated
     * {@code ai.status-update-prompt} template ({@code {itemContext}}, {@code {previousStatus}})
     * rather than the reply prompt. The Ollama call, per-tenant endpoint, circuit breaker and
     * fallback are shared with the reply path.
     *
     * @param item           the freshly looked-up shipment (its current status is the milestone)
     * @param previousStatus the status the customer was last told about (may be null)
     * @return generated or fallback notification body
     */
    public String generateProactiveUpdate(TrackedItem item, String previousStatus) {
        String itemContext = buildItemContext(item);
        String prev = (previousStatus == null || previousStatus.isBlank()) ? "Unknown" : previousStatus;

        String template = settingsService.getString(
                SettingsService.KEY_STATUS_UPDATE_PROMPT, DEFAULT_STATUS_UPDATE_PROMPT);
        String prompt = template
                .replace("{itemContext}", itemContext)
                .replace("{previousStatus}", prev);

        return callOllama(prompt, buildFallbackDraft(item));
    }

    /**
     * Generates a proactive quote follow-up nudge (Phase 2.5).
     *
     * <p>Composed when a sent quote has gone unanswered for the configured delay — it is not a
     * reply to inbound mail, so it uses the dedicated {@code ai.quote-followup-prompt} template
     * ({@code {originalEnquiry}}) rather than the reply prompt, and a quote-specific static
     * fallback (there is no shipment item to fall back on). The Ollama call, per-tenant endpoint,
     * circuit breaker and breaker propagation are shared with the reply path.
     *
     * @param originalTicket the quoted RFQ ticket (its body is the original enquiry context)
     * @return generated or fallback nudge body
     */
    public String generateQuoteFollowUp(EmailTicket originalTicket) {
        String body = originalTicket.getBody() == null ? "" : originalTicket.getBody();
        String enquiry = body.substring(0, Math.min(body.length(), MAX_PROMPT_BODY_CHARS));

        String template = settingsService.getString(
                SettingsService.KEY_QUOTE_FOLLOWUP_PROMPT, DEFAULT_QUOTE_FOLLOWUP_PROMPT);
        String prompt = template.replace("{originalEnquiry}", enquiry);

        return callOllama(prompt, DEFAULT_FOLLOWUP_FALLBACK);
    }

    /**
     * Sends a generate request to Ollama for the CURRENT tenant's endpoint (Phase 2.2 S3) and
     * returns the response text, falling back to a static draft on an empty/failed call.
     *
     * @param prompt        the fully substituted prompt
     * @param fallbackDraft static draft returned when the call yields nothing (precomputed by the
     *                      caller — pure string-building, no I/O)
     * @return the model's reply, or the fallback draft
     * @throws CallNotPermittedException when the breaker is OPEN — propagated so the caller can
     *         decide (the reply pipeline parks the ticket; the proactive pollers skip the entry)
     */
    private String callOllama(String prompt, String fallbackDraft) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", draftingModel);
            requestBody.put("prompt", prompt);
            requestBody.put("stream", false);

            log.debug("Sending drafting request to Ollama model {}", draftingModel);

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
                log.warn("Ollama returned null response during drafting");
                return fallbackDraft;
            }

            String draft = String.valueOf(response.getOrDefault("response", "")).trim();
            if (draft.isEmpty()) {
                log.warn("Ollama returned empty draft");
                return fallbackDraft;
            }

            log.info("Draft generated successfully ({} chars)", draft.length());
            return draft;

        } catch (CallNotPermittedException e) {
            // Breaker OPEN = Ollama is known to be down. Propagate so the pipeline parks the
            // ticket as RETRY_LATER (Phase 1.7) instead of serving the static fallback draft.
            log.warn("Ollama circuit breaker is open — drafting call not attempted");
            throw e;
        } catch (Exception e) {
            log.error("Drafting call to Ollama failed: {}", e.getMessage());
            return fallbackDraft;
        }
    }

    /**
     * Builds a text block of item data for insertion into the prompt.
     *
     * @param item tracked-item data or null
     * @return formatted text with item information
     */
    private String buildItemContext(TrackedItem item) {
        if (item == null) {
            return "Item not found in our system.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Reference: ").append(item.getIdentifier()).append("\n");
        sb.append("Status: ").append(item.getStatus() != null ? item.getStatus() : "Unknown").append("\n");

        if (item.getEta() != null) {
            sb.append("ETA: ").append(item.getEta().format(ETA_FORMATTER)).append("\n");
        } else {
            sb.append("ETA: Not available\n");
        }

        sb.append("Location: ").append(item.getLocation() != null ? item.getLocation() : "Unknown");
        return sb.toString();
    }

    /**
     * Fallback draft — used when Ollama is unavailable.
     * Provides basic information from the DB without AI generation.
     *
     * @param item tracked-item data or null
     * @return static draft with available data
     */
    private String buildFallbackDraft(TrackedItem item) {
        if (item == null) {
            return """
                    Thank you for your enquiry.
                    Unfortunately, we were unable to locate the reference in our system at this time.
                    Please contact our team for further assistance.
                    Kind regards,
                    Customer Service Team
                    """;
        }

        return String.format("""
                Thank you for your enquiry regarding %s.

                Current status: %s
                ETA: %s
                Location: %s

                Please do not hesitate to contact us if you require further information.
                Kind regards,
                Customer Service Team
                """,
                item.getIdentifier(),
                item.getStatus() != null ? item.getStatus() : "N/A",
                item.getEta() != null ? item.getEta().format(ETA_FORMATTER) : "N/A",
                item.getLocation() != null ? item.getLocation() : "N/A"
        );
    }
}
