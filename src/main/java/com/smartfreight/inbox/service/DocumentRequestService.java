package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a DOCUMENT_REQUEST enquiry (Phase 2.7): determines which document the customer is
 * asking for, and looks up the matching stored document.
 *
 * <p>Detection mirrors {@link EmailClassificationService}: one short Qwen call against the current
 * tenant's endpoint, a tolerant parse of the one-word answer, and fail-open to {@link
 * DocumentType#OTHER} on any error. Like the classifier — and unlike the interactive read-only
 * {@code InboxQueryService} — it is part of the email pipeline, so a {@link
 * CallNotPermittedException} from an OPEN breaker is propagated, letting the pipeline park the
 * ticket as RETRY_LATER (Phase 1.7) rather than mislabelling it.
 */
@Service
public class DocumentRequestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentRequestService.class);

    /** Detection is a single token — short timeout, like the classifier. */
    private static final Duration DETECT_TIMEOUT = Duration.ofSeconds(30);

    /** Match the classifier's input budget for the detection prompt. */
    private static final int MAX_DETECT_BODY_CHARS = 1000;

    /**
     * Fallback detection prompt when the DB-backed {@code ai.document-detect-prompt} row is missing
     * or blank. Kept in sync with migration V26. {@code {email}} → the enquiry text.
     */
    private static final String DEFAULT_DETECT_PROMPT = """
            You are a classifier for a freight logistics company. The customer email below asks for one or more
            documents. Decide which SINGLE document type they are primarily requesting and answer with EXACTLY
            ONE of these names, nothing else:

            POD - proof of delivery / delivery note
            BILL_OF_LADING - bill of lading / B/L / sea waybill
            CUSTOMS - customs paperwork, entry documents, C88, T1, clearance papers
            INVOICE_COPY - a copy of an invoice or freight charges
            PACKING_LIST - packing list / manifest of goods
            CMR - CMR consignment note / road transport note
            OTHER - anything else, or unclear

            Customer email:
            {email}

            Answer with one type name only.""";

    private final String model;
    private final OllamaClientProvider ollamaClients;
    private final SettingsService settingsService;
    private final TenantDocumentRepository documentRepository;

    public DocumentRequestService(OllamaClientProvider ollamaClients,
                                  SettingsService settingsService,
                                  TenantDocumentRepository documentRepository,
                                  @Value("${ollama.extraction-model:qwen2.5:7b}") String model) {
        this.ollamaClients = ollamaClients;
        this.settingsService = settingsService;
        this.documentRepository = documentRepository;
        this.model = model;
    }

    /**
     * Determines which document type the enquiry is requesting.
     *
     * @param aiInput the email body plus parsed attachment text
     * @return the detected type, or {@link DocumentType#OTHER} when unclear or on failure
     * @throws CallNotPermittedException when the Ollama breaker is OPEN (propagated so the pipeline
     *                                   parks the ticket — a real verdict is made on retry)
     */
    public DocumentType detectRequestedType(String aiInput) {
        String body = aiInput == null ? "" : aiInput;
        if (body.isBlank()) {
            return DocumentType.OTHER;
        }
        String truncated = body.substring(0, Math.min(body.length(), MAX_DETECT_BODY_CHARS));
        String template = settingsService.getString(
                SettingsService.KEY_DOCUMENT_DETECT_PROMPT, DEFAULT_DETECT_PROMPT);
        String prompt = template.replace("{email}", truncated);

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "prompt", prompt,
                    "stream", false
            );
            WebClient ollamaWebClient = ollamaClients.client();
            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaClients.circuitBreaker().executeSupplier(() -> ollamaWebClient
                    .post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(DETECT_TIMEOUT));
            ollamaClients.recordUsage("document-detect", model, response);

            if (response == null) {
                log.warn("Ollama returned null for document detection — defaulting to OTHER");
                return DocumentType.OTHER;
            }
            String answer = String.valueOf(response.getOrDefault("response", ""));
            DocumentType type = parseType(answer);
            log.info("Document request detected as {}", type);
            return type;
        } catch (CallNotPermittedException e) {
            log.warn("Ollama circuit breaker is open — document detection not attempted");
            throw e;
        } catch (Exception e) {
            log.warn("Document detection failed: {} — defaulting to OTHER", e.getMessage());
            return DocumentType.OTHER;
        }
    }

    /** Looks up the latest stored document of {@code type} for {@code identifier} (current tenant). */
    public Optional<TenantDocument> findDocument(String identifier, DocumentType type) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        return documentRepository.findFirstByIdentifierAndDocTypeOrderByUploadedAtDesc(identifier, type);
    }

    /**
     * Maps the model's free-form answer to a {@link DocumentType}, tolerant of small-model drift.
     *
     * <p>Scans for each type's enum name (e.g. {@code BILL_OF_LADING}), its spaced form
     * ({@code BILL OF LADING}) and its label, and returns the type whose token appears EARLIEST in
     * the answer (first mentioned wins, mirroring {@code InboxQueryService}). Nothing recognised →
     * {@link DocumentType#OTHER}. Package-private for unit testing without a WebClient.
     */
    static DocumentType parseType(String answer) {
        if (answer == null || answer.isBlank()) {
            return DocumentType.OTHER;
        }
        String upper = answer.toUpperCase();
        DocumentType best = DocumentType.OTHER;
        int bestIdx = Integer.MAX_VALUE;
        for (DocumentType type : DocumentType.values()) {
            if (type == DocumentType.OTHER) {
                continue;
            }
            int idx = earliestIndex(upper,
                    type.name(),
                    type.name().replace('_', ' '),
                    type.getLabel().toUpperCase());
            if (idx >= 0 && idx < bestIdx) {
                bestIdx = idx;
                best = type;
            }
        }
        return best;
    }

    private static int earliestIndex(String haystack, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int idx = haystack.indexOf(needle);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }
}
