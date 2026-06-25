package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.WebhookDelivery;
import com.smartfreight.inbox.entity.WebhookSubscription;
import com.smartfreight.inbox.repository.WebhookDeliveryRepository;
import com.smartfreight.inbox.repository.WebhookSubscriptionRepository;
import com.smartfreight.inbox.webhook.UrlSafetyValidator;
import com.smartfreight.inbox.webhook.WebhookEventPublisher;
import com.smartfreight.inbox.webhook.WebhookEventType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * Tenant-facing management of webhook subscriptions (Phase 3.5).
 *
 * <p>Owns the rules the {@code /api/webhooks} controller enforces: the signing secret is encrypted at
 * rest ({@link SecretCipher}, like mailbox passwords) and only ever returned to the operator once — at
 * creation — so they can paste it into their CRM; the URL is SSRF-checked ({@link UrlSafetyValidator});
 * the event-type filter is validated against the {@link WebhookEventType} catalogue. Every method runs
 * in the caller's tenant context, so {@code @TenantId} + RLS scope all reads and writes automatically.
 */
@Service
public class WebhookManagementService {

    /** How many recent deliveries the UI log shows per subscription. */
    private static final int DELIVERY_LOG_LIMIT = 50;

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final SecretCipher secretCipher;
    private final UrlSafetyValidator urlSafetyValidator;
    private final WebhookEventPublisher webhookEventPublisher;
    private final SecureRandom random = new SecureRandom();

    public WebhookManagementService(WebhookSubscriptionRepository subscriptionRepository,
                                    WebhookDeliveryRepository deliveryRepository,
                                    SecretCipher secretCipher,
                                    UrlSafetyValidator urlSafetyValidator,
                                    WebhookEventPublisher webhookEventPublisher) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.secretCipher = secretCipher;
        this.urlSafetyValidator = urlSafetyValidator;
        this.webhookEventPublisher = webhookEventPublisher;
    }

    /** A freshly-created subscription plus its cleartext secret — the only time the secret is exposed. */
    public record Created(WebhookSubscription subscription, String secret) {
    }

    /** The current tenant's subscriptions, newest first. */
    public List<WebhookSubscription> list() {
        return subscriptionRepository.findAllByOrderByIdDesc();
    }

    /** Recent deliveries for one of the tenant's subscriptions (RLS scopes to the tenant). */
    public List<WebhookDelivery> recentDeliveries(Long subscriptionId) {
        return deliveryRepository.findBySubscriptionIdOrderByIdDesc(
                subscriptionId, PageRequest.of(0, DELIVERY_LOG_LIMIT));
    }

    /**
     * Creates a subscription. When {@code providedSecret} is blank a strong one is generated. Returns
     * the cleartext secret once so the operator can configure their receiver's verification.
     */
    @Transactional
    public Created create(String url, List<String> eventTypes, String description, String providedSecret) {
        requireCipher();
        String cleanUrl = requireText(url, "URL is required.");
        urlSafetyValidator.validate(cleanUrl);
        String secret = (providedSecret == null || providedSecret.isBlank())
                ? generateSecret() : providedSecret.trim();

        WebhookSubscription subscription = new WebhookSubscription();
        subscription.setUrl(cleanUrl);
        subscription.setSecretEnc(secretCipher.encrypt(secret));
        subscription.setEventTypes(normaliseEventTypes(eventTypes));
        subscription.setDescription(blankToNull(description));
        subscription.setEnabled(true);
        return new Created(subscriptionRepository.save(subscription), secret);
    }

    /**
     * Updates a subscription's URL / filter / description / enabled flag, and optionally rotates the
     * secret (when {@code newSecret} is non-blank). Throws {@link IllegalArgumentException} if the id is
     * not the tenant's (the lookup is tenant-scoped → empty).
     */
    @Transactional
    public void update(Long id, String url, List<String> eventTypes, String description,
                       boolean enabled, String newSecret) {
        WebhookSubscription subscription = subscriptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Webhook not found."));
        String cleanUrl = requireText(url, "URL is required.");
        urlSafetyValidator.validate(cleanUrl);
        subscription.setUrl(cleanUrl);
        subscription.setEventTypes(normaliseEventTypes(eventTypes));
        subscription.setDescription(blankToNull(description));
        subscription.setEnabled(enabled);
        // Phase 3.5 S2: an operator action clears the auto-disable state. Re-enabling gives the endpoint a
        // clean slate (reset the failure run); a manual disable is not an auto-disable, so drop the reason.
        subscription.setDisabledReason(null);
        if (enabled) {
            subscription.setConsecutiveFailures(0);
        }
        if (newSecret != null && !newSecret.isBlank()) {
            requireCipher();
            subscription.setSecretEnc(secretCipher.encrypt(newSecret.trim()));
        }
        subscription.setUpdatedAt(LocalDateTime.now());
        subscriptionRepository.save(subscription);
    }

    @Transactional
    public void delete(Long id) {
        subscriptionRepository.deleteById(id);
    }

    /**
     * Queues a test delivery to one subscription regardless of its event filter, so the operator can
     * verify the endpoint + signature end to end. Throws if the id is not the tenant's.
     */
    @Transactional
    public void sendTest(Long id) {
        WebhookSubscription subscription = subscriptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Webhook not found."));
        webhookEventPublisher.publishTo(subscription, "ping",
                java.util.Map.of("message", "This is a test event from SmartFreight."));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String normaliseEventTypes(List<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return WebhookEventType.ALL;
        }
        if (eventTypes.stream().anyMatch(t -> WebhookEventType.ALL.equals(t == null ? null : t.trim()))) {
            return WebhookEventType.ALL;
        }
        List<String> valid = eventTypes.stream()
                .map(t -> t == null ? "" : t.trim())
                .filter(t -> !t.isEmpty())
                .distinct()
                .toList();
        for (String t : valid) {
            if (!WebhookEventType.ALL_TYPES.contains(t)) {
                throw new IllegalArgumentException("Unknown event type: " + t);
            }
        }
        return valid.isEmpty() ? WebhookEventType.ALL : String.join(",", valid);
    }

    private String generateSecret() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private void requireCipher() {
        if (!secretCipher.isConfigured()) {
            throw new IllegalStateException(
                    "MAILBOX_ENCRYPTION_KEY is not set — a webhook signing secret cannot be stored.");
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
