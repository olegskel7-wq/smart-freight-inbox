package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.entity.WebhookSubscription;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * A webhook subscription for the React {@code /webhooks} page (Phase 3.5).
 *
 * <p>The signing secret is NOT included — it is shown once at creation and never read back. The CSV
 * {@code event_types} column is split into a list for the UI's type picker.
 */
public record WebhookSubscriptionDto(
        Long id,
        String url,
        List<String> eventTypes,
        String description,
        boolean enabled,
        String disabledReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static WebhookSubscriptionDto from(WebhookSubscription s) {
        List<String> types = (s.getEventTypes() == null || s.getEventTypes().isBlank())
                ? List.of()
                : Arrays.stream(s.getEventTypes().split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
        return new WebhookSubscriptionDto(
                s.getId(), s.getUrl(), types, s.getDescription(), s.isEnabled(),
                s.getDisabledReason(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
