package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TrackedItem;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.repository.TenantRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the exact text {@link AiDraftingService} sends to Ollama and returns on failure: every
 * default prompt, the placeholder substitution order, the append-fallback for stored prompts that
 * predate a placeholder, truncation, the static fallbacks and the request/metric shape. A
 * refactoring of the service must leave every assertion here untouched.
 */
@ExtendWith(MockitoExtension.class)
class AiDraftingPromptCharacterizationTest {

    @Mock private WebClient webClient;
    @Mock private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private WebClient.RequestBodySpec requestBodySpec;
    @Mock private WebClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;
    @Mock private SettingsService settingsService;
    @Mock private OllamaClientProvider ollamaClients;
    @Mock private TenantRepository tenantRepository;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);

    /** No tenant context in these tests → the instruction carries no company name. */
    private static final String INSTRUCTION = "Do not end the email with a sign-off, a name, a job title or a "
            + "company signature: the sender's signature is added automatically when the email is sent.";

    private static final String ITEM_CONTEXT = """
            Reference: MSCU1234567
            Current status: At port (arrived at the terminal and unloaded; not yet through customs)
            ETA: 25 Apr 2026, 10:00 (in the past: the container has already arrived)
            Last recorded location: Felixstowe Terminal 2
            Note: the current status is the authoritative fact. If the last recorded location does not \
            fit the current status, do not mention the location.""";

    private SimpleMeterRegistry meterRegistry;
    private AiDraftingService service;

    @BeforeEach
    void setUp() {
        lenient().when(settingsService.getString(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(ollamaClients.client()).thenReturn(webClient);
        lenient().when(ollamaClients.circuitBreaker()).thenReturn(CircuitBreaker.ofDefaults("ollama-test"));
        meterRegistry = new SimpleMeterRegistry();
        service = new AiDraftingService(new OllamaDraftClient(ollamaClients,
                new ReplySignatureService(tenantRepository), meterRegistry, "llama3.1:8b"),
                settingsService, CLOCK);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubOllama(Mono<Map> response) {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(response);
    }

    private void stubReply(String text) {
        stubOllama(Mono.just(Map.of("response", text, "done", true)));
    }

    private void stubFailure() {
        stubOllama(Mono.error(new RuntimeException("Ollama unavailable")));
    }

    /** The request body sent to Ollama (the stub must have been called once). */
    private Map<?, ?> sentBody() {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec).bodyValue(body.capture());
        return (Map<?, ?>) body.getValue();
    }

    /** The prompt sent to Ollama minus the signature instruction every call appends. */
    private String sentPrompt() {
        String prompt = (String) sentBody().get("prompt");
        String suffix = "\n\n" + INSTRUCTION;
        assertTrue(prompt.endsWith(suffix), "Every prompt ends with the drafting instruction");
        return prompt.substring(0, prompt.length() - suffix.length());
    }

    private static TrackedItem item() {
        return new TrackedItem("MSCU1234567", "AT_PORT",
                LocalDateTime.of(2026, 4, 25, 10, 0), "Felixstowe Terminal 2", null);
    }

    private static EmailTicket ticket(String body) {
        EmailTicket t = new EmailTicket();
        t.setBody(body);
        return t;
    }

    // ── Reply draft: default template ────────────────────────────────────────────────────────────

    @Test
    void replyPrompt_defaultTemplate_noContexts_usesNotesForEverySlot() {
        stubReply("ok");

        service.generateDraft(new DraftRequest("Where is my container?", null, "", "", ""));

        assertEquals("""
                You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
                Write a concise, professional email reply to the customer's inquiry below.
                Use the container information provided. Be helpful, polite, and factual.
                Do NOT make up information that is not in the container data.
                Reply in the same language as the customer's email.

                Container information:
                Item not found in our system.

                Rate information (prices already include our margin — quote them as-is, never mention margin):
                No rate information available for this enquiry. Do not quote any price.

                Examples of how our team has previously replied to similar enquiries (match their tone and approach; ignore any details that do not apply here):
                No past examples available yet.

                Reference information from our knowledge base (use only if relevant to the customer's question; do not invent):
                No reference information available.

                Customer's email:
                Where is my container?

                Write only the email reply body (no subject line, no "Dear..." header unless natural).""",
                sentPrompt());
    }

    @Test
    void replyPrompt_defaultTemplate_contextsAreTrimmedIntoTheirSlots() {
        stubReply("ok");

        service.generateDraft(new DraftRequest("Quote please", item(), "  Rate: GBP 1200\n", "\nEXAMPLES ", " KB "));

        assertEquals("""
                You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
                Write a concise, professional email reply to the customer's inquiry below.
                Use the container information provided. Be helpful, polite, and factual.
                Do NOT make up information that is not in the container data.
                Reply in the same language as the customer's email.

                Container information:
                %s

                Rate information (prices already include our margin — quote them as-is, never mention margin):
                Rate: GBP 1200

                Examples of how our team has previously replied to similar enquiries (match their tone and approach; ignore any details that do not apply here):
                EXAMPLES

                Reference information from our knowledge base (use only if relevant to the customer's question; do not invent):
                KB

                Customer's email:
                Quote please

                Write only the email reply body (no subject line, no "Dear..." header unless natural).""".formatted(ITEM_CONTEXT),
                sentPrompt());
    }

    @Test
    void replyPrompt_nullContexts_behaveLikeEmpty() {
        stubReply("ok");

        service.generateDraft(new DraftRequest("Hi", null, null, null, null));

        String prompt = sentPrompt();
        assertTrue(prompt.contains("No rate information available for this enquiry. Do not quote any price."));
        assertTrue(prompt.contains("No past examples available yet."));
        assertTrue(prompt.contains("No reference information available."));
    }

    @Test
    void replyPrompt_requestOf_hasEmptyContexts() {
        stubReply("ok");

        service.generateDraft(DraftRequest.of("Hi", null));

        assertTrue(sentPrompt().contains("No rate information available"));
    }

    // ── Reply draft: stored templates ────────────────────────────────────────────────────────────

    @Test
    void replyPrompt_storedTemplateWithoutPlaceholders_appendsBlocksInOrder() {
        when(settingsService.getString(eq(SettingsService.KEY_DRAFTING_PROMPT), anyString()))
                .thenReturn("Item: {itemContext}\nEmail: {email}");
        stubReply("ok");

        service.generateDraft(new DraftRequest("Quote please", null, "RATES", "EXAMPLES", "KB"));

        assertEquals("""
                Item: Item not found in our system.
                Email: Quote please

                Rate information (prices already include our margin — quote them as-is, never mention margin):
                RATES

                Examples of how our team has previously replied to similar enquiries (match their tone and approach; ignore any details that do not apply here):
                EXAMPLES

                Reference information from our knowledge base (use only if relevant to the customer's question; do not invent):
                KB""", sentPrompt());
    }

    @Test
    void replyPrompt_storedTemplateWithoutPlaceholders_emptyContexts_appendNothing() {
        when(settingsService.getString(eq(SettingsService.KEY_DRAFTING_PROMPT), anyString()))
                .thenReturn("Email: {email}");
        stubReply("ok");

        service.generateDraft(new DraftRequest("Hi", null, "", "  ", null));

        assertEquals("Email: Hi", sentPrompt());
    }

    @Test
    void replyPrompt_placeholdersReplacedEverywhere() {
        when(settingsService.getString(eq(SettingsService.KEY_DRAFTING_PROMPT), anyString()))
                .thenReturn("{rateContext}|{rateContext}|{email}|{email}");
        stubReply("ok");

        service.generateDraft(new DraftRequest("E", null, "R", "", ""));

        assertEquals("R|R|E|E", sentPrompt());
    }

    @Test
    void replyPrompt_itemDataCannotInjectTrustedPlaceholder() {
        when(settingsService.getString(eq(SettingsService.KEY_DRAFTING_PROMPT), anyString()))
                .thenReturn("{itemContext}|{rateContext}");
        stubReply("ok");
        TrackedItem hostile = new TrackedItem("{rateContext}", "AT_PORT", null, null, null);

        service.generateDraft(new DraftRequest("E", hostile, "R", "", ""));

        String prompt = sentPrompt();
        assertTrue(prompt.startsWith("Reference: {rateContext}\n"), "Item data is inlined after trusted slots");
        assertTrue(prompt.endsWith("|R"));
    }

    @Test
    void replyPrompt_emailTruncatedTo2000Chars() {
        when(settingsService.getString(eq(SettingsService.KEY_DRAFTING_PROMPT), anyString()))
                .thenReturn("{email}");
        stubReply("ok");

        service.generateDraft(new DraftRequest("a".repeat(2000) + "b".repeat(500), null, "", "", ""));

        assertEquals("a".repeat(2000), sentPrompt());
    }

    // ── Proactive status update ──────────────────────────────────────────────────────────────────

    @Test
    void statusUpdatePrompt_defaultTemplate() {
        stubReply("ok");

        service.generateProactiveUpdate(item(), "IN_TRANSIT");

        assertEquals("""
                You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
                Write a short, proactive status update email to notify the customer that their shipment has progressed.
                This is NOT a reply to a customer message — the customer has not just written to you. Open accordingly.
                Use only the shipment information provided. Be helpful, polite and factual. Do NOT invent information.

                Shipment information:
                %s

                Previous status: IN_TRANSIT

                Write only the email body (no subject line). Keep it to a few sentences.""".formatted(ITEM_CONTEXT),
                sentPrompt());
        verify(settingsService).getString(eq(SettingsService.KEY_STATUS_UPDATE_PROMPT), anyString());
    }

    @Test
    void statusUpdatePrompt_nullOrBlankPreviousStatus_isUnknown() {
        when(settingsService.getString(eq(SettingsService.KEY_STATUS_UPDATE_PROMPT), anyString()))
                .thenReturn("{previousStatus}");
        stubReply("ok");

        service.generateProactiveUpdate(item(), "  ");

        assertEquals("Unknown", sentPrompt());
    }

    @Test
    void statusUpdate_ollamaFails_returnsItemFallback() {
        stubFailure();

        assertEquals("""
                Thank you for your enquiry regarding MSCU1234567.

                Current status: AT_PORT
                ETA: 25 Apr 2026, 10:00
                Location: Felixstowe Terminal 2

                Please do not hesitate to contact us if you require further information.
                """, service.generateProactiveUpdate(item(), "IN_TRANSIT"));
    }

    // ── Quote follow-up ──────────────────────────────────────────────────────────────────────────

    @Test
    void quoteFollowUpPrompt_defaultTemplate() {
        stubReply("ok");

        service.generateQuoteFollowUp(ticket("2x40ft Felixstowe to Leeds"));

        assertEquals("""
                You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
                You sent this customer a freight quote a couple of days ago and have not heard back.
                Write a short, polite follow-up email gently checking whether they have any questions about the quote and whether they would like to proceed.
                This is NOT a reply to a new customer message — you are reaching out proactively. Open accordingly.
                Do NOT restate prices or invent any new figures. Keep it warm, brief and low-pressure.

                The customer's original enquiry was:
                2x40ft Felixstowe to Leeds

                Write only the email body (no subject line). Keep it to a few sentences.""", sentPrompt());
        verify(settingsService).getString(eq(SettingsService.KEY_QUOTE_FOLLOWUP_PROMPT), anyString());
    }

    @Test
    void quoteFollowUpPrompt_nullBodyIsEmpty() {
        when(settingsService.getString(eq(SettingsService.KEY_QUOTE_FOLLOWUP_PROMPT), anyString()))
                .thenReturn("[{originalEnquiry}]");
        stubReply("ok");

        service.generateQuoteFollowUp(ticket(null));
        assertEquals("[]", sentPrompt());
    }

    @Test
    void quoteFollowUpPrompt_longBodyTruncatedTo2000Chars() {
        when(settingsService.getString(eq(SettingsService.KEY_QUOTE_FOLLOWUP_PROMPT), anyString()))
                .thenReturn("{originalEnquiry}");
        stubReply("ok");

        service.generateQuoteFollowUp(ticket("q".repeat(2600)));

        assertEquals("q".repeat(2000), sentPrompt());
    }

    @Test
    void quoteFollowUp_ollamaFails_returnsStaticNudge() {
        stubFailure();

        assertEquals("""
                Dear Customer,

                I hope you are well. I am following up on the freight quote we sent you recently.
                Please let me know if you have any questions or if you would like to go ahead — we would be glad to help.
                """, service.generateQuoteFollowUp(ticket("Quote please")));
    }

    // ── Document reply ───────────────────────────────────────────────────────────────────────────

    @Test
    void documentPrompt_defaultTemplate_attached() {
        stubReply("ok");

        service.generateDocumentReply("POD please", DocumentType.POD, item(), true);

        assertEquals("""
                You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
                The customer has requested a document. Write a concise, professional email reply.
                Document requested: Proof of Delivery
                The requested document is attached to this email.
                Use only the information provided. Be helpful, polite and factual. Do NOT invent information.

                Shipment information:
                %s

                Customer's email:
                POD please

                Write only the email reply body (no subject line).""".formatted(ITEM_CONTEXT), sentPrompt());
        verify(settingsService).getString(eq(SettingsService.KEY_DOCUMENT_COVER_PROMPT), anyString());
    }

    @Test
    void documentPrompt_missing_saysBeingRetrieved_andTruncatesEmail() {
        when(settingsService.getString(eq(SettingsService.KEY_DOCUMENT_COVER_PROMPT), anyString()))
                .thenReturn("{documentType}|{documentStatus}|{itemContext}|{email}");
        stubReply("ok");

        service.generateDocumentReply("x".repeat(2100), DocumentType.POD, null, false);

        assertEquals("Proof of Delivery|We do not currently have this document on file; it is being retrieved "
                + "and will follow shortly.|Item not found in our system.|" + "x".repeat(2000), sentPrompt());
    }

    @Test
    void documentReply_ollamaFails_fallbackPerBranch() {
        stubFailure();

        assertEquals("""
                Thank you for your enquiry.

                Please find the requested Proof of Delivery attached to this email.

                Do not hesitate to contact us if you require anything further.
                """, service.generateDocumentReply("POD", DocumentType.POD, item(), true));
        assertEquals("""
                Thank you for your enquiry.

                We have received your request for the Proof of Delivery and are retrieving it. We will send it to you shortly.
                """, service.generateDocumentReply("POD", DocumentType.POD, item(), false));
    }

    // ── Reply fallbacks ──────────────────────────────────────────────────────────────────────────

    @Test
    void replyFallback_itemNotFound() {
        stubFailure();

        assertEquals("""
                Thank you for your enquiry.
                Unfortunately, we were unable to locate the reference in our system at this time.
                Please contact our team for further assistance.
                """, service.generateDraft(new DraftRequest("Where?", null, "", "", "")));
    }

    @Test
    void replyFallback_itemWithMissingFields_usesNA() {
        stubFailure();

        assertEquals("""
                Thank you for your enquiry regarding MSCU1234567.

                Current status: N/A
                ETA: N/A
                Location: N/A

                Please do not hesitate to contact us if you require further information.
                """, service.generateDraft(new DraftRequest("Where?",
                new TrackedItem("MSCU1234567", null, null, null, null), "", "", "")));
    }

    // ── Ollama call ──────────────────────────────────────────────────────────────────────────────

    @Test
    void ollamaCall_requestShape_metricAndUsage() {
        service = new AiDraftingService(new OllamaDraftClient(ollamaClients,
                new ReplySignatureService(tenantRepository), meterRegistry, "custom-model:3b"),
                settingsService, CLOCK);
        Map<String, Object> response = Map.of("response", "Draft", "done", true);
        stubOllama(Mono.just(response));

        String draft = service.generateDraft(new DraftRequest("Hi", null, "", "", ""));

        assertEquals("Draft", draft);
        verify(requestBodyUriSpec).uri("/api/generate");
        Map<?, ?> body = sentBody();
        assertEquals("custom-model:3b", body.get("model"));
        assertEquals(false, body.get("stream"));
        assertEquals(3, body.size());
        verify(ollamaClients).recordUsage("drafting", "custom-model:3b", response);
        // The timer's model tag is fixed, whatever model is configured.
        Timer timer = meterRegistry.find("smartfreight_ollama_request_seconds")
                .tags("model", "llama3.1:8b", "operation", "drafting").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void ollamaCall_nullResponse_returnsFallback() {
        stubOllama(Mono.empty());

        String draft = service.generateDraft(new DraftRequest("Where?", null, "", "", ""));

        assertTrue(draft.startsWith("Thank you for your enquiry.\nUnfortunately"));
        verify(ollamaClients).recordUsage(eq("drafting"), eq("llama3.1:8b"), isNull());
    }

    @Test
    void ollamaCall_missingOrBlankResponseField_returnsFallback() {
        Map<String, Object> noField = new HashMap<>();
        noField.put("done", true);
        stubOllama(Mono.just(noField));

        assertTrue(service.generateDraft(new DraftRequest("Where?", null, "", "", ""))
                .startsWith("Thank you for your enquiry."));
    }

    @Test
    void ollamaCall_onlySignOff_strippedToEmpty_returnsFallback() {
        stubReply("Best regards,\n[Your Name]");

        assertTrue(service.generateDraft(new DraftRequest("Where?", null, "", "", ""))
                .startsWith("Thank you for your enquiry."));
    }

    @Test
    void ollamaCall_replyIsTrimmed() {
        stubReply("\n  Your container is at port.  \n");

        assertEquals("Your container is at port.",
                service.generateDraft(new DraftRequest("Where?", null, "", "", "")));
    }
}
