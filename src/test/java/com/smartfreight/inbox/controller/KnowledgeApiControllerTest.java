package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.KnowledgeDoc;
import com.smartfreight.inbox.service.KnowledgeBaseService;
import com.smartfreight.inbox.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for the JSON knowledge API (Phase 3.9). No security/tenant filter here
 * (covered by KeycloakJwtRoleConverterTest / TenantFilterTest); this verifies the DTO snapshot, the
 * feature flag, validation (blank → 400) and mutation-returns-snapshot.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeApiControllerTest {

    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private SettingsService settingsService;

    private MockMvc mockMvc;

    private static KnowledgeDoc doc(Long id, String title, String content) {
        KnowledgeDoc d = new KnowledgeDoc();
        d.setId(id);
        d.setTitle(title);
        d.setContent(content);
        d.setSource("manual");
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        return d;
    }

    @BeforeEach
    void setUp() {
        when(settingsService.isFeatureEnabled(eq(KnowledgeBaseService.FEATURE_KEY), anyBoolean()))
                .thenReturn(true);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new KnowledgeApiController(knowledgeBaseService, settingsService))
                .build();
    }

    @Test
    void getReturnsSnapshotWithDocuments() throws Exception {
        when(knowledgeBaseService.list()).thenReturn(List.of(
                doc(7L, "Opening hours", "Mon–Fri 9am–5pm.")));

        mockMvc.perform(get("/api/knowledge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureEnabled").value(true))
                .andExpect(jsonPath("$.documents[0].id").value(7))
                .andExpect(jsonPath("$.documents[0].title").value("Opening hours"))
                .andExpect(jsonPath("$.documents[0].content").value("Mon–Fri 9am–5pm."))
                .andExpect(jsonPath("$.documents[0].source").value("manual"));
    }

    @Test
    void getReflectsDisabledFeatureFlag() throws Exception {
        when(settingsService.isFeatureEnabled(eq(KnowledgeBaseService.FEATURE_KEY), anyBoolean()))
                .thenReturn(false);
        when(knowledgeBaseService.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/knowledge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureEnabled").value(false))
                .andExpect(jsonPath("$.documents").isEmpty());
    }

    @Test
    void createReturnsSnapshot() throws Exception {
        when(knowledgeBaseService.list()).thenReturn(List.of(doc(1L, "Booking", "Email us to book.")));

        mockMvc.perform(post("/api/knowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Booking\",\"content\":\"Email us to book.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].title").value("Booking"));

        verify(knowledgeBaseService).create("Booking", "Email us to book.");
    }

    @Test
    void createBlankTitleReturns400() throws Exception {
        doThrow(new IllegalArgumentException("Title is required."))
                .when(knowledgeBaseService).create(eq(""), eq("content"));

        mockMvc.perform(post("/api/knowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"content\":\"content\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturnsSnapshot() throws Exception {
        when(knowledgeBaseService.list()).thenReturn(List.of(doc(3L, "Updated", "New text.")));

        mockMvc.perform(put("/api/knowledge/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated\",\"content\":\"New text.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].id").value(3));

        verify(knowledgeBaseService).update(3L, "Updated", "New text.");
    }

    @Test
    void updateUnknownIdReturns400() throws Exception {
        doThrow(new IllegalArgumentException("Knowledge document not found"))
                .when(knowledgeBaseService).update(eq(99L), eq("t"), eq("c"));

        mockMvc.perform(put("/api/knowledge/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"c\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRemovesDocumentAndReturnsSnapshot() throws Exception {
        when(knowledgeBaseService.list()).thenReturn(List.of());

        mockMvc.perform(delete("/api/knowledge/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").isEmpty());

        verify(knowledgeBaseService).delete(9L);
    }
}
