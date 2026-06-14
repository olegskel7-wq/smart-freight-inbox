package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TrainingExample;
import com.smartfreight.inbox.repository.TrainingExampleRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Captures operator corrections as training examples (Phase 3.1 — AI Feedback Loop).
 *
 * <p>When an operator edits an AI draft and sends it, {@link #captureIfEdited} records the pair
 * "AI draft → operator final" against the enquiry the AI saw, embedding the enquiry best-effort
 * for later semantic retrieval. Capture is implicit and diff-gated (Levity-style): only edits that
 * change the draft beyond {@code feedback.min-change-ratio} are stored, so trivial typo fixes do
 * not pollute the dataset.
 *
 * <p>Session 2 adds {@code retrieveExamples} here — pgvector cosine search scoped by ticket type —
 * and injects the matches into the drafting prompt. Capture is gated by
 * {@code feature.ai-feedback-loop} (default ON): it never emails a customer unsupervised.
 */
@Service
public class FeedbackLoopService {

    /** Feature flag — see V28. ON by default: capture/injection is always operator-reviewed. */
    public static final String FEATURE_KEY = "feature.ai-feedback-loop";

    private static final Logger log = LoggerFactory.getLogger(FeedbackLoopService.class);

    private final TrainingExampleRepository repository;
    private final EmbeddingService embeddingService;
    private final SettingsService settingsService;
    private final Counter capturedCounter;
    private final double minChangeRatio;

    public FeedbackLoopService(TrainingExampleRepository repository,
                               EmbeddingService embeddingService,
                               SettingsService settingsService,
                               MeterRegistry meterRegistry,
                               @Value("${feedback.min-change-ratio:0.1}") double minChangeRatio) {
        this.repository = repository;
        this.embeddingService = embeddingService;
        this.settingsService = settingsService;
        this.minChangeRatio = minChangeRatio;
        this.capturedCounter = Counter.builder("smartfreight_training_examples_captured_total")
                .description("Operator corrections captured as training examples")
                .register(meterRegistry);
    }

    /**
     * Records a training example if the operator meaningfully edited the AI draft before sending.
     *
     * <p>Called from the send path AFTER a successful send, inside the same tenant-scoped
     * transaction (the example row is stamped with the current tenant by {@code @TenantId}). Never
     * throws — feedback capture must not break the operator's send flow.
     *
     * @param ticket the just-sent ticket; {@code draftReply} now holds the operator's final text
     */
    public void captureIfEdited(EmailTicket ticket) {
        try {
            if (!settingsService.isFeatureEnabled(FEATURE_KEY, true)) {
                return;
            }
            // Proactive tickets (status updates, quote nudges) are platform-authored, not replies
            // to a customer — operator edits there are not the reactive-drafting signal we learn from.
            if (ticket.isProactive()) {
                return;
            }
            String aiDraft = ticket.getAiOriginalDraft();
            String operatorFinal = ticket.getDraftReply();
            if (aiDraft == null || aiDraft.isBlank() || operatorFinal == null || operatorFinal.isBlank()) {
                return;
            }
            // Diff gate: skip trivial edits (typo fixes) — they add noise, not signal.
            if (changeRatio(aiDraft, operatorFinal) < minChangeRatio) {
                log.debug("Ticket {} edit below min-change-ratio — not captured", ticket.getId());
                return;
            }

            String enquiry = buildEnquiry(ticket);
            if (enquiry.isBlank()) {
                return;
            }

            TrainingExample example = new TrainingExample();
            example.setSourceTicketId(ticket.getId());
            example.setTicketType(ticket.getTicketType());
            example.setEnquiryText(enquiry);
            example.setAiDraft(aiDraft);
            example.setOperatorFinal(operatorFinal);
            TrainingExample saved = repository.save(example);

            // Best-effort embedding — a missing one must never lose the correction (backfillable).
            Optional<float[]> embedding = embeddingService.embed(enquiry);
            embedding.ifPresent(vec ->
                    repository.setEmbedding(saved.getId(), EmbeddingService.toVectorLiteral(vec)));

            capturedCounter.increment();
            log.info("Captured training example {} from ticket {} (type {}, embedded={})",
                    saved.getId(), ticket.getId(), ticket.getTicketType(), embedding.isPresent());

        } catch (Exception e) {
            // Capture is auxiliary — never break the send flow.
            log.warn("Failed to capture training example for ticket {}: {}",
                    ticket.getId(), e.getMessage());
        }
    }

    /** The input the AI saw: email body + parsed attachment text (mirrors the pipeline). */
    private String buildEnquiry(EmailTicket ticket) {
        String body = ticket.getBody() != null ? ticket.getBody() : "";
        String attachments = ticket.getAttachmentText();
        if (attachments == null || attachments.isBlank()) {
            return body;
        }
        return body + "\n\n" + attachments;
    }

    /**
     * Normalised edit distance between two strings, in [0.0, 1.0]: 0 = identical, 1 = completely
     * different. Used to tell a meaningful correction from a typo fix. Package-private for unit tests.
     */
    static double changeRatio(String a, String b) {
        if (a.equals(b)) {
            return 0.0;
        }
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 0.0;
        }
        return (double) levenshtein(a, b) / maxLen;
    }

    /** Standard Levenshtein distance with a rolling single-row buffer (O(n*m) time, O(m) space). */
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
