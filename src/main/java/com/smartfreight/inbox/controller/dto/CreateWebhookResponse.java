package com.smartfreight.inbox.controller.dto;

/**
 * Response to creating a webhook subscription (Phase 3.5). Carries the cleartext signing {@code secret}
 * EXACTLY ONCE — it is never readable again — alongside the refreshed page {@code snapshot}, so the SPA
 * can both show the secret for copying and re-render the list in one round trip.
 */
public record CreateWebhookResponse(String secret, WebhooksResponse snapshot) {
}
