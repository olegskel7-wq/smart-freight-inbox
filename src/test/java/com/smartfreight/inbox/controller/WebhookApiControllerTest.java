package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.WebhookDelivery;
import com.smartfreight.inbox.entity.WebhookDeliveryStatus;
import com.smartfreight.inbox.entity.WebhookSubscription;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.service.WebhookManagementService;
import com.smartfreight.inbox.webhook.UrlSafetyValidator;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for the webhook JSON API (Phase 3.5). Security/tenant filtering is covered
 * elsewhere; this verifies the snapshot DTO (no secret leaked, event catalogue exposed), the one-time
 * secret on create, validation mapping to 400, and the test / deliveries endpoints.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookApiControllerTest {

    @Mock private WebhookManagementService webhookService;
    @Mock private SecretCipher secretCipher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setId(1L);
        sub.setUrl("https://hooks.example.com/h");
        sub.setEventTypes("*");
        sub.setEnabled(true);
        sub.setCreatedAt(LocalDateTime.now());
        sub.setUpdatedAt(LocalDateTime.now());
        when(webhookService.list()).thenReturn(List.of(sub));
        when(secretCipher.isConfigured()).thenReturn(true);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new WebhookApiController(webhookService, secretCipher))
                .build();
    }

    @Test
    void getReturnsSnapshotWithCatalogueAndNoSecret() throws Exception {
        mockMvc.perform(get("/api/webhooks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions[0].id").value(1))
                .andExpect(jsonPath("$.subscriptions[0].url").value("https://hooks.example.com/h"))
                .andExpect(jsonPath("$.subscriptions[0].eventTypes[0]").value("*"))
                // The secret must never appear in the snapshot.
                .andExpect(jsonPath("$.subscriptions[0].secret").doesNotExist())
                .andExpect(jsonPath("$.availableEventTypes[0]").value("ticket.created"))
                .andExpect(jsonPath("$.secretConfigured").value(true));
    }

    @Test
    void createReturnsOneTimeSecretAndSnapshot() throws Exception {
        WebhookSubscription created = new WebhookSubscription();
        created.setId(2L);
        when(webhookService.create(eq("https://8.8.8.8/h"), any(), any(), any()))
                .thenReturn(new WebhookManagementService.Created(created, "whsec_generated"));

        mockMvc.perform(post("/api/webhooks")
                        .contentType("application/json")
                        .content("{\"url\":\"https://8.8.8.8/h\",\"eventTypes\":[\"*\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value("whsec_generated"))
                .andExpect(jsonPath("$.snapshot.subscriptions[0].id").value(1));
    }

    @Test
    void createWithUnsafeUrlIsBadRequest() throws Exception {
        when(webhookService.create(any(), any(), any(), any()))
                .thenThrow(new UrlSafetyValidator.UnsafeUrlException("Webhook URL must use HTTPS."));

        mockMvc.perform(post("/api/webhooks")
                        .contentType("application/json")
                        .content("{\"url\":\"http://x/h\",\"eventTypes\":[\"*\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturnsSnapshot() throws Exception {
        mockMvc.perform(post("/api/webhooks/1")
                        .contentType("application/json")
                        .content("{\"url\":\"https://8.8.8.8/h\",\"eventTypes\":[\"*\"],\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions[0].id").value(1));

        verify(webhookService).update(eq(1L), eq("https://8.8.8.8/h"), any(), any(), eq(false), any());
    }

    @Test
    void updateUnknownIdIsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("Webhook not found."))
                .when(webhookService).update(eq(9L), any(), any(), any(), eq(true), any());

        mockMvc.perform(post("/api/webhooks/9")
                        .contentType("application/json")
                        .content("{\"url\":\"https://8.8.8.8/h\",\"eventTypes\":[\"*\"],\"enabled\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturnsSnapshot() throws Exception {
        mockMvc.perform(delete("/api/webhooks/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions[0].id").value(1));

        verify(webhookService).delete(1L);
    }

    @Test
    void testEndpointQueuesAndReturnsSnapshot() throws Exception {
        mockMvc.perform(post("/api/webhooks/1/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions[0].id").value(1));

        verify(webhookService).sendTest(1L);
    }

    @Test
    void testEndpointUnknownIdIsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("Webhook not found.")).when(webhookService).sendTest(404L);

        mockMvc.perform(post("/api/webhooks/404/test"))
                .andExpect(status().isBadRequest());

        verify(webhookService, never()).list();
    }

    @Test
    void deliveriesEndpointMapsRows() throws Exception {
        WebhookDelivery d = new WebhookDelivery();
        d.setId(50L);
        d.setEventType("ticket.created");
        d.setStatus(WebhookDeliveryStatus.DELIVERED);
        d.setAttempts(1);
        d.setResponseStatus(200);
        d.setCreatedAt(LocalDateTime.now());
        d.setNextAttemptAt(LocalDateTime.now());
        when(webhookService.recentDeliveries(1L)).thenReturn(List.of(d));

        mockMvc.perform(get("/api/webhooks/1/deliveries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(50))
                .andExpect(jsonPath("$[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$[0].responseStatus").value(200));
    }
}
