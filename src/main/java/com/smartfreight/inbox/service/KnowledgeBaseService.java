package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.KnowledgeDoc;
import com.smartfreight.inbox.repository.KnowledgeDocRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Manages the per-tenant knowledge base and retrieves it at draft time (Phase 3.9 — Knowledge-base
 * deflection).
 *
 * <p>Operators store free-text knowledge ({@link #create}/{@link #update}); each document is embedded
 * best-effort with the same local Ollama ({@code nomic-embed-text}) as the feedback loop, so the
 * knowledge never leaves the server. When the pipeline drafts a non-document reply,
 * {@link #retrieveContext} embeds the incoming enquiry, ranks this tenant's documents by pgvector
 * cosine within a relevance threshold, and returns a block injected into the drafting prompt — so a
 * typical "how / where / when" enquiry gets a substantive answer even without structured backend data.
 *
 * <p>Gated by {@code feature.knowledge-base} (default ON): grounding a draft is always
 * operator-reviewed and never emails a customer unsupervised (analytics / feedback-loop /
 * document-request precedent). Sibling of {@link FeedbackLoopService}.
 */
@Service
public class KnowledgeBaseService {

    /** Feature flag — see V33. ON by default: grounding is always operator-reviewed. */
    public static final String FEATURE_KEY = "feature.knowledge-base";

    /** Provenance value for operator text entry (S2 file-upload sets the file name instead). */
    private static final String SOURCE_MANUAL = "manual";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    /** Per-document cap on the content text in the injected block — keeps the prompt bounded. */
    private static final int MAX_DOC_CHARS = 2000;

    /** Title length must fit the {@code knowledge_doc.title VARCHAR(255)} column (V33). */
    private static final int MAX_TITLE_LENGTH = 255;

    private final KnowledgeDocRepository repository;
    private final EmbeddingService embeddingService;
    private final SettingsService settingsService;
    private final Counter injectedCounter;
    private final int retrieveCount;
    private final double maxDistance;

    public KnowledgeBaseService(KnowledgeDocRepository repository,
                                EmbeddingService embeddingService,
                                SettingsService settingsService,
                                MeterRegistry meterRegistry,
                                @Value("${knowledge.retrieve-count:3}") int retrieveCount,
                                @Value("${knowledge.max-distance:0.55}") double maxDistance) {
        this.repository = repository;
        this.embeddingService = embeddingService;
        this.settingsService = settingsService;
        this.retrieveCount = retrieveCount;
        this.maxDistance = maxDistance;
        this.injectedCounter = Counter.builder("smartfreight_knowledge_injected_total")
                .description("Knowledge documents injected into drafting prompts as grounding")
                .register(meterRegistry);
    }

    /** The current tenant's knowledge documents, newest first. */
    public List<KnowledgeDoc> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Stores a new knowledge document and embeds it best-effort.
     *
     * <p>The embedding is auxiliary: a failure (Ollama down / breaker open) leaves the document stored
     * without one (backfillable) rather than failing the create — mirrors {@code captureIfEdited}.
     *
     * <p>Deliberately NOT {@code @Transactional}: the row is committed by {@code repository.save} on its
     * own, then the embedding round-trip runs OUTSIDE any transaction so the (potentially slow) Ollama
     * call never holds a pooled DB connection. The vector write ({@code setEmbedding}) gets its own
     * short transaction. The two steps are independent precisely because the embedding is best-effort.
     *
     * @param title   short label (required)
     * @param content the knowledge text (required)
     * @return the saved document
     * @throws IllegalArgumentException if title or content is blank
     */
    public KnowledgeDoc create(String title, String content) {
        String t = requireTitle(title);
        String c = requireText(content, "Content");

        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle(t);
        doc.setContent(c);
        doc.setSource(SOURCE_MANUAL);
        KnowledgeDoc saved = repository.save(doc);
        embed(saved);
        log.info("Created knowledge document {} ('{}')", saved.getId(), t);
        return saved;
    }

    /**
     * Updates an existing knowledge document and re-embeds it best-effort.
     *
     * <p>Not {@code @Transactional} for the same reason as {@link #create}: the Ollama embedding call
     * must not hold a DB connection.
     *
     * @param id      the document id (must belong to the current tenant — RLS + @TenantId scope it)
     * @param title   new label (required)
     * @param content new text (required)
     * @return the updated document
     * @throws IllegalArgumentException if the id is unknown for this tenant, or title/content is blank
     */
    public KnowledgeDoc update(Long id, String title, String content) {
        String t = requireTitle(title);
        String c = requireText(content, "Content");

        KnowledgeDoc doc = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Knowledge document not found"));
        doc.setTitle(t);
        doc.setContent(c);
        doc.setUpdatedAt(LocalDateTime.now());
        KnowledgeDoc saved = repository.save(doc);
        embed(saved);
        log.info("Updated knowledge document {} ('{}')", saved.getId(), t);
        return saved;
    }

    /**
     * Removes one document. Idempotent: an unknown or cross-tenant id (RLS + {@code @TenantId} scope
     * {@code existsById} to the current tenant) is a no-op, so the API answers a clean snapshot instead
     * of the 500 a bare {@code deleteById} would raise on a missing row.
     */
    public void delete(Long id) {
        if (repository.existsById(id)) {
            repository.deleteById(id);
        }
    }

    /**
     * Retrieves knowledge relevant to the incoming enquiry, formatted as a grounding block for the
     * drafting prompt (Phase 3.9).
     *
     * <p>Embeds the enquiry and ranks this tenant's documents by pgvector cosine within the relevance
     * threshold. Returns "" when the feature is off, the enquiry cannot be embedded, nothing clears the
     * threshold, or anything fails — retrieval is auxiliary and must never break the pipeline (the
     * drafting call itself still propagates an open breaker to park the ticket).
     *
     * <p>Unlike {@code FeedbackLoopService.retrieveExamples} there is deliberately NO recency fallback
     * when the enquiry is unembeddable: a "recent" FAQ is not a "relevant" FAQ, and an off-topic
     * document grounding a reply is worse than none — so without an embedding we inject nothing.
     *
     * @param enquiry the exact input the AI is drafting from (body + attachment text)
     * @return a formatted knowledge block, or "" if none
     */
    public String retrieveContext(String enquiry) {
        // Guard before embedding so a disabled feature / blank enquiry costs no Ollama call.
        if (!settingsService.isFeatureEnabled(FEATURE_KEY, true)
                || enquiry == null || enquiry.isBlank() || retrieveCount <= 0) {
            return "";
        }
        return retrieveContext(enquiry, () -> embeddingService.embed(enquiry));
    }

    /**
     * Same as {@link #retrieveContext(String)} but pulls the enquiry embedding from a supplier instead
     * of producing its own.
     *
     * <p>The pipeline shares ONE memoised supplier between this and
     * {@link FeedbackLoopService#retrieveExamples(com.smartfreight.inbox.entity.enums.TicketType,
     * String, Supplier)}, so a single draft makes at most one embedding call across both retrievers —
     * and only if a retriever passes its own feature/blank checks and actually asks for it. As in the
     * self-embedding overload there is deliberately NO recency fallback: an empty embedding means
     * relevance cannot be judged, so nothing is injected.
     *
     * @param enquiry           the exact input the AI is drafting from (body + attachment text)
     * @param embeddingSupplier supplies the enquiry's embedding (empty if it could not be produced)
     * @return a formatted knowledge block, or "" if none
     */
    public String retrieveContext(String enquiry, Supplier<Optional<float[]>> embeddingSupplier) {
        try {
            if (!settingsService.isFeatureEnabled(FEATURE_KEY, true)) {
                return "";
            }
            if (enquiry == null || enquiry.isBlank() || retrieveCount <= 0) {
                return "";
            }
            // No recency fallback (unlike V28): an unembeddable enquiry means we cannot judge relevance,
            // and an irrelevant document pollutes the draft — better to ground on nothing.
            Optional<float[]> embedding = embeddingSupplier.get();
            if (embedding.isEmpty()) {
                return "";
            }
            List<KnowledgeDoc> matches = repository.findSimilar(
                    EmbeddingService.toVectorLiteral(embedding.get()), maxDistance, retrieveCount);
            if (matches.isEmpty()) {
                return "";
            }
            injectedCounter.increment(matches.size());
            log.debug("Injecting {} knowledge documents into draft", matches.size());
            return buildKnowledgeContext(matches);
        } catch (Exception e) {
            // Retrieval is auxiliary — a failure must never break drafting.
            log.warn("Failed to retrieve knowledge context: {}", e.getMessage());
            return "";
        }
    }

    /** Best-effort embedding of title + content; a missing one is never fatal (backfillable). */
    private void embed(KnowledgeDoc doc) {
        Optional<float[]> embedding = embeddingService.embed(doc.getTitle() + "\n" + doc.getContent());
        embedding.ifPresent(vec ->
                repository.setEmbedding(doc.getId(), EmbeddingService.toVectorLiteral(vec)));
        if (embedding.isEmpty()) {
            log.debug("Knowledge document {} stored without an embedding (will backfill)", doc.getId());
        }
    }

    /** Formats matched documents as title/answer pairs; headers live in AiDraftingService. */
    private String buildKnowledgeContext(List<KnowledgeDoc> docs) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeDoc doc : docs) {
            sb.append("Title:\n").append(doc.getTitle()).append("\n");
            sb.append("Answer:\n").append(truncate(doc.getContent())).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_DOC_CHARS ? text : text.substring(0, MAX_DOC_CHARS);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.trim();
    }

    /** Required, plus bounded to the title column width so an over-long title answers 400, not 500. */
    private static String requireTitle(String value) {
        String t = requireText(value, "Title");
        if (t.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Title must be at most " + MAX_TITLE_LENGTH + " characters.");
        }
        return t;
    }
}
