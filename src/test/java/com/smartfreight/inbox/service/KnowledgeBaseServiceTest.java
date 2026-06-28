package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.KnowledgeDoc;
import com.smartfreight.inbox.repository.KnowledgeDocRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-tests {@link KnowledgeBaseService}: best-effort embedding on create/update, validation, and the
 * retrieval gate (Phase 3.9). The defining difference from the feedback loop (V28) is asserted here:
 * an unembeddable enquiry returns "" with NO recency fallback. All collaborators are mocked.
 */
class KnowledgeBaseServiceTest {

    private KnowledgeDocRepository repository;
    private EmbeddingService embeddingService;
    private SettingsService settingsService;
    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeDocRepository.class);
        embeddingService = mock(EmbeddingService.class);
        settingsService = mock(SettingsService.class);
        when(settingsService.isFeatureEnabled(eq(KnowledgeBaseService.FEATURE_KEY), anyBoolean())).thenReturn(true);
        when(repository.save(any(KnowledgeDoc.class))).thenAnswer(inv -> {
            KnowledgeDoc d = inv.getArgument(0);
            d.setId(42L);
            return d;
        });
        when(embeddingService.embed(anyString())).thenReturn(Optional.empty());
        service = new KnowledgeBaseService(repository, embeddingService, settingsService,
                new SimpleMeterRegistry(), 3, 0.55);
    }

    private KnowledgeDoc doc(String title, String content) {
        KnowledgeDoc d = new KnowledgeDoc();
        d.setTitle(title);
        d.setContent(content);
        return d;
    }

    // ---- create / update -----------------------------------------------------------------

    @Test
    void create_savesDocument() {
        service.create("Opening hours", "We are open Monday to Friday, 9am to 5pm.");

        verify(repository).save(any(KnowledgeDoc.class));
    }

    @Test
    void create_withEmbedding_writesVector() {
        when(embeddingService.embed(anyString())).thenReturn(Optional.of(new float[]{0.1f, 0.2f}));

        service.create("Opening hours", "Mon–Fri 9–5.");

        verify(repository).save(any(KnowledgeDoc.class));
        verify(repository).setEmbedding(eq(42L), anyString());
    }

    @Test
    void create_withoutEmbedding_stillSaves_noVectorWritten() {
        service.create("Opening hours", "Mon–Fri 9–5.");

        verify(repository).save(any(KnowledgeDoc.class));
        verify(repository, never()).setEmbedding(anyLong(), anyString());
    }

    @Test
    void create_blankTitle_throws() {
        assertThatThrownBy(() -> service.create("  ", "content"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void create_blankContent_throws() {
        assertThatThrownBy(() -> service.create("title", ""))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void update_unknownId_throws() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, "title", "content"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_existing_savesAndReEmbeds() {
        when(repository.findById(5L)).thenReturn(Optional.of(doc("Old", "Old content")));
        when(embeddingService.embed(anyString())).thenReturn(Optional.of(new float[]{0.3f}));

        service.update(5L, "New title", "New content");

        verify(repository).save(any(KnowledgeDoc.class));
        verify(repository).setEmbedding(eq(42L), anyString());
    }

    // ---- delete --------------------------------------------------------------------------

    @Test
    void delete_existing_removes() {
        when(repository.existsById(5L)).thenReturn(true);

        service.delete(5L);

        verify(repository).deleteById(5L);
    }

    @Test
    void delete_unknownOrCrossTenant_isNoOp() {
        // existsById is RLS + @TenantId scoped, so an unknown / other-tenant id reads false → no-op
        // (no EmptyResultDataAccessException → no 500).
        when(repository.existsById(99L)).thenReturn(false);

        service.delete(99L);

        verify(repository, never()).deleteById(anyLong());
    }

    // ---- retrieval -----------------------------------------------------------------------

    @Test
    void retrieve_withEmbedding_formatsBlock() {
        when(embeddingService.embed(anyString())).thenReturn(Optional.of(new float[]{0.1f, 0.2f}));
        when(repository.findSimilar(anyString(), eq(0.55), eq(3)))
                .thenReturn(List.of(doc("Opening hours", "Mon–Fri 9am–5pm.")));

        String block = service.retrieveContext("When are you open?");

        verify(repository).findSimilar(anyString(), eq(0.55), eq(3));
        assertThat(block)
                .contains("Title:")
                .contains("Opening hours")
                .contains("Answer:")
                .contains("Mon–Fri 9am–5pm.");
    }

    @Test
    void retrieve_withoutEmbedding_returnsEmpty_noRecencyFallback() {
        // The key difference from V28: no recency fallback — an unembeddable enquiry grounds on nothing.
        when(embeddingService.embed(anyString())).thenReturn(Optional.empty());

        assertThat(service.retrieveContext("When are you open?")).isEmpty();
        verify(repository, never()).findSimilar(anyString(), anyDouble(), anyInt());
    }

    @Test
    void retrieve_noMatches_returnsEmpty() {
        when(embeddingService.embed(anyString())).thenReturn(Optional.of(new float[]{0.1f}));
        when(repository.findSimilar(anyString(), anyDouble(), anyInt())).thenReturn(List.of());

        assertThat(service.retrieveContext("anything")).isEmpty();
    }

    @Test
    void retrieve_featureDisabled_returnsEmpty_andDoesNotQuery() {
        when(settingsService.isFeatureEnabled(eq(KnowledgeBaseService.FEATURE_KEY), anyBoolean())).thenReturn(false);

        assertThat(service.retrieveContext("When are you open?")).isEmpty();
        verify(embeddingService, never()).embed(anyString());
        verify(repository, never()).findSimilar(anyString(), anyDouble(), anyInt());
    }

    @Test
    void retrieve_blankEnquiry_returnsEmpty() {
        assertThat(service.retrieveContext("   ")).isEmpty();
        verify(repository, never()).findSimilar(anyString(), anyDouble(), anyInt());
    }
}
