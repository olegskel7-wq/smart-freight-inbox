package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TrainingExample;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.TrainingExampleRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * Unit-tests the capture gate of {@link FeedbackLoopService}: which operator edits become training
 * examples, and the best-effort embedding write. All collaborators are mocked.
 */
class FeedbackLoopServiceTest {

    private TrainingExampleRepository repository;
    private EmbeddingService embeddingService;
    private SettingsService settingsService;
    private FeedbackLoopService service;

    @BeforeEach
    void setUp() {
        repository = mock(TrainingExampleRepository.class);
        embeddingService = mock(EmbeddingService.class);
        settingsService = mock(SettingsService.class);
        when(settingsService.isFeatureEnabled(eq(FeedbackLoopService.FEATURE_KEY), anyBoolean())).thenReturn(true);
        when(repository.save(any(TrainingExample.class))).thenAnswer(inv -> {
            TrainingExample e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });
        when(embeddingService.embed(anyString())).thenReturn(Optional.empty());
        service = new FeedbackLoopService(repository, embeddingService, settingsService,
                new SimpleMeterRegistry(), 0.1, 3);
    }

    private EmailTicket ticket(String aiDraft, String operatorFinal) {
        EmailTicket t = new EmailTicket();
        t.setId(7L);
        t.setBody("Where is container MSCU1234567?");
        t.setTicketType(TicketType.STATUS_UPDATE);
        t.setAiOriginalDraft(aiDraft);
        t.setDraftReply(operatorFinal);
        return t;
    }

    @Test
    void meaningfulEdit_isCaptured() {
        service.captureIfEdited(ticket(
                "Your container is at the port.",
                "Hi John, good news — your container MSCU1234567 has arrived at Felixstowe and clears customs tomorrow."));

        verify(repository).save(any(TrainingExample.class));
    }

    @Test
    void meaningfulEdit_withEmbedding_writesVector() {
        when(embeddingService.embed(anyString())).thenReturn(Optional.of(new float[]{0.1f, 0.2f}));

        service.captureIfEdited(ticket(
                "Your container is at the port.",
                "Hi John, good news — your container has arrived at Felixstowe and clears customs tomorrow."));

        verify(repository).save(any(TrainingExample.class));
        verify(repository).setEmbedding(eq(42L), anyString());
    }

    @Test
    void trivialEdit_isNotCaptured() {
        // One-character fix → ratio well below 0.1.
        service.captureIfEdited(ticket(
                "Your container is at the port now.",
                "Your container is at the port now!"));

        verify(repository, never()).save(any());
    }

    @Test
    void unchangedDraft_isNotCaptured() {
        String same = "Your container is at the port.";
        service.captureIfEdited(ticket(same, same));

        verify(repository, never()).save(any());
    }

    @Test
    void featureDisabled_isNotCaptured() {
        when(settingsService.isFeatureEnabled(eq(FeedbackLoopService.FEATURE_KEY), anyBoolean())).thenReturn(false);

        service.captureIfEdited(ticket("AI draft here.", "Completely different operator reply text."));

        verify(repository, never()).save(any());
    }

    @Test
    void proactiveTicket_isNotCaptured() {
        EmailTicket t = ticket("AI draft here.", "Completely different operator reply text.");
        t.setProactive(true);

        service.captureIfEdited(t);

        verify(repository, never()).save(any());
    }

    @Test
    void missingAiOriginalDraft_isNotCaptured() {
        service.captureIfEdited(ticket(null, "Operator wrote this from scratch."));

        verify(repository, never()).save(any());
    }

    @Test
    void changeRatio_identicalIsZero_disjointIsHigh() {
        assertThat(FeedbackLoopService.changeRatio("abc", "abc")).isZero();
        assertThat(FeedbackLoopService.changeRatio("abcdefghij", "abcdefghij")).isZero();
        assertThat(FeedbackLoopService.changeRatio("hello world", "xyzzy plover")).isGreaterThan(0.5);
    }

    // ---- Phase 3.1 S2: retrieval -------------------------------------------------------

    private TrainingExample example(String enquiry, String operatorFinal) {
        TrainingExample e = new TrainingExample();
        e.setTicketType(TicketType.RFQ);
        e.setEnquiryText(enquiry);
        e.setAiDraft("AI draft");
        e.setOperatorFinal(operatorFinal);
        return e;
    }

    @Test
    void retrieve_withEmbedding_usesCosineSearch_andFormatsPairs() {
        when(embeddingService.embed(anyString())).thenReturn(Optional.of(new float[]{0.1f, 0.2f}));
        when(repository.findSimilar(eq("RFQ"), anyString(), eq(3)))
                .thenReturn(List.of(example("Quote London to Leeds?", "We can quote £450 for that lane.")));

        String block = service.retrieveExamples(TicketType.RFQ, "Need a quote London to Leeds");

        verify(repository).findSimilar(eq("RFQ"), anyString(), eq(3));
        verify(repository, never()).findRecent(anyString(), anyInt());
        assertThat(block)
                .contains("Customer enquiry:")
                .contains("Quote London to Leeds?")
                .contains("Approved reply:")
                .contains("We can quote £450 for that lane.");
    }

    @Test
    void retrieve_withoutEmbedding_fallsBackToRecency() {
        when(embeddingService.embed(anyString())).thenReturn(Optional.empty());
        when(repository.findRecent(eq("RFQ"), eq(3)))
                .thenReturn(List.of(example("Older enquiry", "Older approved reply")));

        String block = service.retrieveExamples(TicketType.RFQ, "Need a quote");

        verify(repository).findRecent(eq("RFQ"), eq(3));
        verify(repository, never()).findSimilar(anyString(), anyString(), anyInt());
        assertThat(block).contains("Older approved reply");
    }

    @Test
    void retrieve_nullType_passesNullToQuery() {
        when(embeddingService.embed(anyString())).thenReturn(Optional.of(new float[]{0.3f}));
        when(repository.findSimilar(isNull(), anyString(), eq(3))).thenReturn(List.of());

        String block = service.retrieveExamples(null, "Some enquiry");

        verify(repository).findSimilar(isNull(), anyString(), eq(3));
        assertThat(block).isEmpty();
    }

    @Test
    void retrieve_noMatches_returnsEmpty() {
        when(embeddingService.embed(anyString())).thenReturn(Optional.of(new float[]{0.1f}));
        when(repository.findSimilar(anyString(), anyString(), anyInt())).thenReturn(List.of());

        assertThat(service.retrieveExamples(TicketType.RFQ, "enquiry")).isEmpty();
    }

    @Test
    void retrieve_featureDisabled_returnsEmpty_andDoesNotQuery() {
        when(settingsService.isFeatureEnabled(eq(FeedbackLoopService.FEATURE_KEY), anyBoolean())).thenReturn(false);

        assertThat(service.retrieveExamples(TicketType.RFQ, "enquiry")).isEmpty();
        verify(repository, never()).findSimilar(anyString(), anyString(), anyInt());
        verify(repository, never()).findRecent(anyString(), anyInt());
    }

    @Test
    void retrieve_blankEnquiry_returnsEmpty() {
        assertThat(service.retrieveExamples(TicketType.RFQ, "  ")).isEmpty();
        verify(repository, never()).findSimilar(anyString(), anyString(), anyInt());
    }
}
