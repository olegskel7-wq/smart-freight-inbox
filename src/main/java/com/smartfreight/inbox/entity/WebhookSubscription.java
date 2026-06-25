package com.smartfreight.inbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * A tenant's outbound webhook endpoint (Phase 3.5).
 *
 * <p>CRM-agnostic: the tenant points this at any HTTPS receiver — their CRM's catch-hook, Zapier,
 * Make, or a bespoke endpoint — and SmartFreight POSTs a signed JSON envelope whenever a subscribed
 * event fires. {@code eventTypes} is a CSV of {@link com.smartfreight.inbox.webhook.WebhookEventType}
 * names (or {@code "*"} for all); {@code secretEnc} is the HMAC signing secret encrypted at rest with
 * {@link com.smartfreight.inbox.service.SecretCipher}. Guarded by both isolation layers: Hibernate
 * {@code @TenantId} for entity queries and the V30 RLS policy for everything else.
 */
@Entity
@Table(name = "webhook_subscription")
@Getter
@Setter
@NoArgsConstructor
public class WebhookSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant — assigned on insert, filters every entity query. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** Destination URL — HTTPS only (validated on save by {@code UrlSafetyValidator}). */
    @Column(name = "url", nullable = false)
    private String url;

    /** AES-GCM-encrypted HMAC secret; the cleartext never leaves the server after creation. */
    @Column(name = "secret_enc", nullable = false)
    private String secretEnc;

    /** CSV of subscribed event-type names, or {@code "*"} for all. */
    @Column(name = "event_types", nullable = false)
    private String eventTypes = "*";

    /** Optional human label shown in the UI (e.g. "HubSpot via Zapier"). */
    @Column(name = "description")
    private String description;

    /** When false, new events are not queued for this endpoint (already-queued deliveries still drain). */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * Count of fully-failed (DEAD) deliveries in a row (Phase 3.5 S2). The dispatcher increments it on
     * each DEAD delivery and resets it to 0 on any DELIVERED; when it crosses {@code webhook.auto-disable-after}
     * the endpoint is auto-disabled. Reset to 0 whenever the operator re-enables the subscription.
     */
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    /**
     * Why the subscription was auto-disabled, or {@code null} if it is enabled / was disabled by the
     * operator. Lets the UI distinguish "we turned this off because it's dead" from a manual toggle.
     */
    @Column(name = "disabled_reason")
    private String disabledReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** True when this subscription wants the given event type (explicit listing or the {@code "*"} wildcard). */
    public boolean subscribesTo(String eventType) {
        if (eventTypes == null || eventTypes.isBlank()) {
            return false;
        }
        for (String part : eventTypes.split(",")) {
            String trimmed = part.trim();
            if (trimmed.equals(com.smartfreight.inbox.webhook.WebhookEventType.ALL)
                    || trimmed.equalsIgnoreCase(eventType)) {
                return true;
            }
        }
        return false;
    }
}
