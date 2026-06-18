package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.TrainingExample;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.TrainingExampleRepository;
import com.smartfreight.inbox.service.FeedbackLoopService;
import com.smartfreight.inbox.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for the JSON training-examples API (Phase 3.2). No security/tenant filter
 * here (covered by KeycloakJwtRoleConverterTest / TenantFilterTest); this verifies the DTO snapshot
 * (server-side preview abbreviation, null-type → null), the feature flag and delete-returns-snapshot.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrainingExampleApiControllerTest {

    @Mock private TrainingExampleRepository repository;
    @Mock private SettingsService settingsService;

    private MockMvc mockMvc;

    private static TrainingExample example(Long id, TicketType type, String enquiry,
                                           String aiDraft, String operatorFinal) {
        TrainingExample e = new TrainingExample();
        e.setId(id);
        e.setTicketType(type);
        e.setEnquiryText(enquiry);
        e.setAiDraft(aiDraft);
        e.setOperatorFinal(operatorFinal);
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    @BeforeEach
    void setUp() {
        when(settingsService.isFeatureEnabled(eq(FeedbackLoopService.FEATURE_KEY), anyBoolean()))
                .thenReturn(true);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new TrainingExampleApiController(repository, settingsService))
                .build();
    }

    @Test
    void getReturnsSnapshotWithExamples() throws Exception {
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                example(7L, TicketType.RFQ, "How much to ship?", "AI draft text", "Operator reply")));

        mockMvc.perform(get("/api/training-examples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureEnabled").value(true))
                .andExpect(jsonPath("$.examples[0].id").value(7))
                .andExpect(jsonPath("$.examples[0].ticketType").value("RFQ"))
                .andExpect(jsonPath("$.examples[0].enquiryPreview").value("How much to ship?"))
                .andExpect(jsonPath("$.examples[0].aiDraftPreview").value("AI draft text"))
                .andExpect(jsonPath("$.examples[0].operatorFinalPreview").value("Operator reply"));
    }

    @Test
    void getAbbreviatesLongTextAndMapsNullType() throws Exception {
        String longEnquiry = "x".repeat(200);
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                example(8L, null, longEnquiry, "short", "short")));

        mockMvc.perform(get("/api/training-examples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examples[0].ticketType").doesNotExist())
                // 120-char cap: 119 chars + the ellipsis.
                .andExpect(jsonPath("$.examples[0].enquiryPreview").value("x".repeat(119) + "…"));
    }

    @Test
    void getReflectsDisabledFeatureFlag() throws Exception {
        when(settingsService.isFeatureEnabled(eq(FeedbackLoopService.FEATURE_KEY), anyBoolean()))
                .thenReturn(false);
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        mockMvc.perform(get("/api/training-examples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureEnabled").value(false))
                .andExpect(jsonPath("$.examples").isEmpty());
    }

    @Test
    void deleteRemovesExampleAndReturnsSnapshot() throws Exception {
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                example(9L, TicketType.GENERAL, "enquiry", "ai", "final")));

        mockMvc.perform(delete("/api/training-examples/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examples[0].id").value(9));

        verify(repository).deleteById(9L);
    }
}
