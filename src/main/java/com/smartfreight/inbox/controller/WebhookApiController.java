package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.CreateWebhookResponse;
import com.smartfreight.inbox.controller.dto.WebhookDeliveryDto;
import com.smartfreight.inbox.controller.dto.WebhookSubscriptionDto;
import com.smartfreight.inbox.controller.dto.WebhooksResponse;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.service.WebhookManagementService;
import com.smartfreight.inbox.webhook.UrlSafetyValidator;
import com.smartfreight.inbox.webhook.WebhookEventType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * JSON API backing the React {@code /webhooks} page (Phase 3.5): manage outbound webhook subscriptions
 * and inspect their delivery log. ADMIN-gated by the resource-server chain
 * ({@code /api/webhooks/**} → ROLE_ADMIN in {@link com.smartfreight.inbox.config.SecurityConfig}),
 * mirroring the other tenant-admin surfaces (Settings, Users). Validation lives in
 * {@link WebhookManagementService}; rejections surface as {@code 400} with a plain message.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookApiController {

    private final WebhookManagementService webhookService;
    private final SecretCipher secretCipher;

    public WebhookApiController(WebhookManagementService webhookService, SecretCipher secretCipher) {
        this.webhookService = webhookService;
        this.secretCipher = secretCipher;
    }

    /** Create-subscription request. {@code secret} is optional — blank means generate a strong one. */
    public record CreateWebhookRequest(String url, List<String> eventTypes, String description, String secret) {
    }

    /** Update request. {@code secret} non-blank rotates the signing secret; blank keeps the current one. */
    public record UpdateWebhookRequest(String url, List<String> eventTypes, String description,
                                       boolean enabled, String secret) {
    }

    /** GET /api/webhooks — the tenant's subscriptions, the event catalogue, and cipher availability. */
    @GetMapping
    public WebhooksResponse get() {
        return buildResponse();
    }

    /** POST /api/webhooks — creates a subscription; returns the one-time secret + refreshed snapshot. */
    @PostMapping
    public CreateWebhookResponse create(@RequestBody CreateWebhookRequest req) {
        try {
            WebhookManagementService.Created created = webhookService.create(
                    req.url(), req.eventTypes(), req.description(), req.secret());
            return new CreateWebhookResponse(created.secret(), buildResponse());
        } catch (UrlSafetyValidator.UnsafeUrlException | IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** POST /api/webhooks/{id} — updates a subscription; returns the refreshed snapshot. */
    @PostMapping("/{id}")
    public WebhooksResponse update(@PathVariable Long id, @RequestBody UpdateWebhookRequest req) {
        try {
            webhookService.update(id, req.url(), req.eventTypes(), req.description(), req.enabled(), req.secret());
            return buildResponse();
        } catch (UrlSafetyValidator.UnsafeUrlException | IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** DELETE /api/webhooks/{id} — removes a subscription (and its deliveries via cascade). */
    @DeleteMapping("/{id}")
    public WebhooksResponse delete(@PathVariable Long id) {
        try {
            webhookService.delete(id);
            return buildResponse();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** POST /api/webhooks/{id}/test — queues a test delivery to verify the endpoint end to end. */
    @PostMapping("/{id}/test")
    public WebhooksResponse test(@PathVariable Long id) {
        try {
            webhookService.sendTest(id);
            return buildResponse();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** GET /api/webhooks/{id}/deliveries — recent delivery attempts for the subscription (newest first). */
    @GetMapping("/{id}/deliveries")
    public List<WebhookDeliveryDto> deliveries(@PathVariable Long id) {
        return webhookService.recentDeliveries(id).stream().map(WebhookDeliveryDto::from).toList();
    }

    private WebhooksResponse buildResponse() {
        List<WebhookSubscriptionDto> subscriptions = webhookService.list().stream()
                .map(WebhookSubscriptionDto::from)
                .toList();
        return new WebhooksResponse(subscriptions, WebhookEventType.ALL_TYPES, secretCipher.isConfigured());
    }
}
