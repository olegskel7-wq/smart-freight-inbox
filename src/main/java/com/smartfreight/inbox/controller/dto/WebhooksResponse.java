package com.smartfreight.inbox.controller.dto;

import java.util.List;

/**
 * Snapshot for the React {@code /webhooks} page (Phase 3.5): the tenant's subscriptions, the catalogue
 * of subscribable event types (so the client never hard-codes them), and whether the signing-secret
 * cipher is configured (when false the UI warns that subscriptions cannot be created).
 */
public record WebhooksResponse(
        List<WebhookSubscriptionDto> subscriptions,
        List<String> availableEventTypes,
        boolean secretConfigured) {
}
