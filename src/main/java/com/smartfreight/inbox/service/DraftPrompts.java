package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TrackedItem;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Prompt templates for {@link AiDraftingService} and the substitution that fills them.
 *
 * <p>Each {@code DEFAULT_*} template is the fallback used when the tenant's DB-backed prompt row is
 * missing or blank, and is kept in sync with the migration that seeds that row. Pure string
 * building — no I/O, no Spring.
 */
final class DraftPrompts {

    /** ETA date formatter for display (e.g. "15 Apr 2025, 14:30"). English locale — month names
     * must not follow the server's default locale, drafts are quoted to UK customers. */
    static final DateTimeFormatter ETA_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

    /**
     * What each port-service status code means, worded for the model (lifecycle from port-service's
     * {@code ContainerStatus}). The label leads, so the model quotes it instead of the raw code.
     */
    private static final Map<String, String> STATUS_MEANINGS = Map.of(
            "IN_TRANSIT", "In transit (travelling by sea or road; not yet arrived at the port)",
            "AT_PORT", "At port (arrived at the terminal and unloaded; not yet through customs)",
            "CUSTOMS", "Held at customs (undergoing customs inspection; not yet released)",
            "RELEASED", "Released by customs (cleared and ready for collection)",
            "DELIVERED", "Delivered (handed over to the final recipient)");

    /** Statuses after arrival: a past ETA is history there, not a stale promise. */
    private static final Set<String> ARRIVED_STATUSES = Set.of("AT_PORT", "CUSTOMS", "RELEASED", "DELIVERED");

    /** Maximum email body length sent to the LLM. */
    private static final int MAX_PROMPT_BODY_CHARS = 2000;

    /**
     * Reply prompt ({@code ai.drafting-prompt}), kept in sync with migration V9. {@code {itemContext}}
     * → the looked-up item data, {@code {email}} → the (truncated) email body, and one slot per
     * context block of the {@link DraftRequest}.
     */
    static final String DEFAULT_DRAFTING_PROMPT = """
            You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
            Write a concise, professional email reply to the customer's inquiry below.
            Use the container information provided. Be helpful, polite, and factual.
            Do NOT make up information that is not in the container data.
            Reply in the same language as the customer's email.

            Container information:
            {itemContext}

            Rate information (prices already include our margin — quote them as-is, never mention margin):
            {rateContext}

            Examples of how our team has previously replied to similar enquiries (match their tone and approach; ignore any details that do not apply here):
            {exampleContext}

            Reference information from our knowledge base (use only if relevant to the customer's question; do not invent):
            {knowledgeContext}

            Customer's email:
            {email}

            Write only the email reply body (no subject line, no "Dear..." header unless natural).""";

    /** Substituted for {@code {rateContext}} when no rates matched the enquiry. */
    private static final String NO_RATES_NOTE = "No rate information available for this enquiry. Do not quote any price.";

    /** Header for the rate block when appended to a stored prompt that predates {@code {rateContext}}. */
    private static final String RATE_HEADER =
            "Rate information (prices already include our margin — quote them as-is, never mention margin):";

    /** Substituted for {@code {exampleContext}} when no past corrections matched the enquiry. */
    private static final String NO_EXAMPLES_NOTE = "No past examples available yet.";

    /** Header for the example block when appended to a stored prompt that predates {@code {exampleContext}}. */
    private static final String EXAMPLE_HEADER =
            "Examples of how our team has previously replied to similar enquiries "
            + "(match their tone and approach; ignore any details that do not apply here):";

    /** Substituted for {@code {knowledgeContext}} when no knowledge documents matched the enquiry. */
    private static final String NO_KNOWLEDGE_NOTE = "No reference information available.";

    /** Header for the knowledge block when appended to a stored prompt that predates {@code {knowledgeContext}}. */
    private static final String KNOWLEDGE_HEADER =
            "Reference information from our knowledge base "
            + "(use only if relevant to the customer's question; do not invent):";

    /**
     * Proactive status-update prompt ({@code ai.status-update-prompt}), kept in sync with migration
     * V22. {@code {itemContext}} → the looked-up shipment data, {@code {previousStatus}} → the status
     * the customer was last told about.
     */
    static final String DEFAULT_STATUS_UPDATE_PROMPT = """
            You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
            Write a short, proactive status update email to notify the customer that their shipment has progressed.
            This is NOT a reply to a customer message — the customer has not just written to you. Open accordingly.
            Use only the shipment information provided. Be helpful, polite and factual. Do NOT invent information.

            Shipment information:
            {itemContext}

            Previous status: {previousStatus}

            Write only the email body (no subject line). Keep it to a few sentences.""";

    /**
     * Proactive quote follow-up prompt ({@code ai.quote-followup-prompt}), kept in sync with
     * migration V24. {@code {originalEnquiry}} → the customer's original RFQ text.
     */
    static final String DEFAULT_QUOTE_FOLLOWUP_PROMPT = """
            You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
            You sent this customer a freight quote a couple of days ago and have not heard back.
            Write a short, polite follow-up email gently checking whether they have any questions about the quote and whether they would like to proceed.
            This is NOT a reply to a new customer message — you are reaching out proactively. Open accordingly.
            Do NOT restate prices or invent any new figures. Keep it warm, brief and low-pressure.

            The customer's original enquiry was:
            {originalEnquiry}

            Write only the email body (no subject line). Keep it to a few sentences.""";

    /**
     * Document-request reply prompt ({@code ai.document-cover-prompt}), kept in sync with migration
     * V26. {@code {documentType}} → the requested document, {@code {documentStatus}} → whether it is
     * attached or being retrieved, {@code {itemContext}} → shipment data, {@code {email}} → enquiry.
     */
    static final String DEFAULT_DOCUMENT_COVER_PROMPT = """
            You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
            The customer has requested a document. Write a concise, professional email reply.
            Document requested: {documentType}
            {documentStatus}
            Use only the information provided. Be helpful, polite and factual. Do NOT invent information.

            Shipment information:
            {itemContext}

            Customer's email:
            {email}

            Write only the email reply body (no subject line).""";

    /** {@code {documentStatus}} text when the requested document is attached to the reply. */
    private static final String DOC_STATUS_ATTACHED =
            "The requested document is attached to this email.";

    /** {@code {documentStatus}} text when no matching document is on file (operator escalation). */
    private static final String DOC_STATUS_MISSING =
            "We do not currently have this document on file; it is being retrieved and will follow shortly.";

    private DraftPrompts() {
    }

    /**
     * Fills a reply template.
     *
     * <p>Context blocks are defensive about prompt history: when the stored template lacks a
     * block's placeholder (it was seeded before that one existed) but the request carries content,
     * the block is appended with its header instead of being silently dropped — so no migration of
     * the stored prompt is required.
     *
     * @param template    the tenant's reply template
     * @param request     the enquiry and its context blocks
     * @param itemContext the item block from {@link #itemContext}
     */
    static String reply(String template, DraftRequest request, String itemContext) {
        // Substitute the TRUSTED context placeholders first, while the prompt still holds only template
        // text. The untrusted customer email ({email}) and backend item data ({itemContext}) are inlined
        // LAST (below) — so an email that happens to contain a literal {rateContext}/{exampleContext}/
        // {knowledgeContext} token cannot get tenant rate/example/knowledge content injected at a
        // customer-chosen position (String.replace is global; substituting email first would expose it).
        String prompt = template;
        prompt = fillOrAppend(prompt, template, "{rateContext}", request.rateContext(), NO_RATES_NOTE, RATE_HEADER);
        prompt = fillOrAppend(prompt, template, "{exampleContext}", request.exampleContext(),
                NO_EXAMPLES_NOTE, EXAMPLE_HEADER);
        prompt = fillOrAppend(prompt, template, "{knowledgeContext}", request.knowledgeContext(),
                NO_KNOWLEDGE_NOTE, KNOWLEDGE_HEADER);

        // Untrusted input goes in last: any trusted-placeholder tokens it contains are now inert text.
        return prompt
                .replace("{itemContext}", itemContext)
                .replace("{email}", truncate(request.emailBody()));
    }

    /**
     * Puts a context block into its placeholder — or the "nothing matched" note when it is empty —
     * or, when the template has no such placeholder, appends a non-empty block under its header.
     */
    private static String fillOrAppend(String prompt, String template, String placeholder, String context,
                                       String emptyNote, String header) {
        String block = context == null ? "" : context.trim();
        if (template.contains(placeholder)) {
            return prompt.replace(placeholder, block.isEmpty() ? emptyNote : block);
        }
        return block.isEmpty() ? prompt : prompt + "\n\n" + header + "\n" + block;
    }

    /** Fills a proactive status-update template; a missing previous status reads "Unknown". */
    static String statusUpdate(String template, String itemContext, String previousStatus) {
        String prev = (previousStatus == null || previousStatus.isBlank()) ? "Unknown" : previousStatus;
        return template
                .replace("{itemContext}", itemContext)
                .replace("{previousStatus}", prev);
    }

    /** Fills a quote follow-up template with the original enquiry (null body = empty). */
    static String quoteFollowUp(String template, String enquiryBody) {
        return template.replace("{originalEnquiry}", truncate(enquiryBody == null ? "" : enquiryBody));
    }

    /** Fills a document-request template; {@code attached} picks the {@code {documentStatus}} wording. */
    static String documentReply(String template, String docLabel, boolean attached,
                                String itemContext, String emailBody) {
        return template
                .replace("{documentType}", docLabel)
                .replace("{documentStatus}", attached ? DOC_STATUS_ATTACHED : DOC_STATUS_MISSING)
                .replace("{itemContext}", itemContext)
                .replace("{email}", truncate(emailBody));
    }

    private static String truncate(String body) {
        return body.substring(0, Math.min(body.length(), MAX_PROMPT_BODY_CHARS));
    }

    /**
     * Builds a text block of item data for insertion into the prompt.
     *
     * <p>Deterministic rather than clever: small local models take every line at face value, so the
     * block spells out what a status code means, says when an ETA is already in the past, and tells
     * the model which fact wins when the backend's status and location disagree (the port-service
     * simulator advances status without moving the location — "In transit" at "HMRC Examination
     * Bay 3" — and a real TOS feed can lag the same way).
     *
     * @param item tracked-item data or null
     * @param now  the current time, for the stale-ETA flag
     * @return formatted text with item information
     */
    static String itemContext(TrackedItem item, LocalDateTime now) {
        if (item == null) {
            return "Item not found in our system.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Reference: ").append(item.getIdentifier()).append("\n");
        sb.append("Current status: ").append(describeStatus(item.getStatus())).append("\n");
        if (item.getUpdatedAt() != null) {
            sb.append("Status last updated: ").append(item.getUpdatedAt().format(ETA_FORMATTER)).append("\n");
        }

        if (item.getEta() == null) {
            sb.append("ETA: Not available\n");
        } else {
            sb.append("ETA: ").append(item.getEta().format(ETA_FORMATTER));
            if (item.getEta().isBefore(now)) {
                // Set.of rejects contains(null): a status-less item counts as not yet arrived.
                sb.append(item.getStatus() != null && ARRIVED_STATUSES.contains(item.getStatus())
                        ? " (in the past: the container has already arrived)"
                        : " (OUT OF DATE: this date has already passed. Do not quote it as an upcoming "
                          + "arrival; say an updated ETA will be confirmed.)");
            }
            sb.append("\n");
        }

        sb.append("Last recorded location: ").append(item.getLocation() != null ? item.getLocation() : "Unknown")
                .append("\n");
        sb.append("Note: the current status is the authoritative fact. If the last recorded location does not "
                + "fit the current status, do not mention the location.");
        return sb.toString();
    }

    /** Plain-English meaning of a port-service status code; unknown codes are just humanised. */
    private static String describeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "Unknown";
        }
        String meaning = STATUS_MEANINGS.get(status);
        if (meaning != null) {
            return meaning;
        }
        String words = status.replace('_', ' ').toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
