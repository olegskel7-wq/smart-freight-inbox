package com.smartfreight.inbox.webhook;

import com.smartfreight.inbox.entity.WebhookDelivery;
import com.smartfreight.inbox.entity.WebhookDeliveryStatus;
import com.smartfreight.inbox.entity.WebhookSubscription;
import com.smartfreight.inbox.repository.WebhookDeliveryRepository;
import com.smartfreight.inbox.repository.WebhookSubscriptionRepository;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Delivers queued webhook events to tenant endpoints (Phase 3.5).
 *
 * <p>The send half of the webhook outbox, mirroring {@code OutboxRelay}: a scheduled poll locks due
 * {@code PENDING} deliveries with {@code FOR UPDATE SKIP LOCKED} (multi-instance safe) inside a
 * system-context transaction, then POSTs each with an HMAC signature. Unlike the RabbitMQ relay, a
 * failure does NOT stop the batch — endpoints are independent, so one dead receiver must not hold up
 * another tenant's deliveries; each row is settled on its own.
 *
 * <p>Reliability: a 2xx marks the row {@code DELIVERED}; anything else bumps {@code attempts}, records
 * the reason, and reschedules with exponential backoff until the attempt budget is spent, at which
 * point the row becomes {@code DEAD} (kept for the UI log, never retried). At-least-once — the receiver
 * dedupes on the {@code X-SmartFreight-Delivery} (event) id.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final SecretCipher secretCipher;
    private final WebhookSigner signer;
    private final UrlSafetyValidator urlSafetyValidator;
    private final WebClient webhookWebClient;
    private final TransactionTemplate transactionTemplate;
    private final Counter deliveredCounter;
    private final Counter failedCounter;
    private final Counter deadCounter;
    private final Counter autoDisabledCounter;

    @Value("${webhook.batch-size:20}")
    private int batchSize;

    @Value("${webhook.max-attempts:8}")
    private int maxAttempts;

    /** Auto-disable a subscription after this many fully-failed (DEAD) deliveries in a row (Phase 3.5 S2). */
    @Value("${webhook.auto-disable-after:3}")
    private int autoDisableAfter;

    /** First retry waits this long; each further retry doubles it, capped by {@link #backoffCapSeconds}. */
    @Value("${webhook.backoff-base-seconds:60}")
    private long backoffBaseSeconds;

    @Value("${webhook.backoff-cap-seconds:21600}")
    private long backoffCapSeconds;

    @Value("${webhook.timeout-seconds:10}")
    private long timeoutSeconds;

    public WebhookDispatcher(WebhookDeliveryRepository deliveryRepository,
                             WebhookSubscriptionRepository subscriptionRepository,
                             SecretCipher secretCipher,
                             WebhookSigner signer,
                             UrlSafetyValidator urlSafetyValidator,
                             WebClient webhookWebClient,
                             TransactionTemplate transactionTemplate,
                             MeterRegistry meterRegistry) {
        this.deliveryRepository = deliveryRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.secretCipher = secretCipher;
        this.signer = signer;
        this.urlSafetyValidator = urlSafetyValidator;
        this.webhookWebClient = webhookWebClient;
        this.transactionTemplate = transactionTemplate;
        this.deliveredCounter = Counter.builder("smartfreight_webhook_deliveries_total")
                .tag("result", "delivered").register(meterRegistry);
        this.failedCounter = Counter.builder("smartfreight_webhook_deliveries_total")
                .tag("result", "failed").register(meterRegistry);
        this.deadCounter = Counter.builder("smartfreight_webhook_deliveries_total")
                .tag("result", "dead").register(meterRegistry);
        this.autoDisabledCounter = Counter.builder("smartfreight_webhook_subscriptions_disabled_total")
                .description("Webhook subscriptions auto-disabled after consecutive failed deliveries")
                .register(meterRegistry);
    }

    /**
     * Polls and sends due deliveries. {@code fixedDelay} (not rate): the next poll starts only after the
     * previous finished, so slow receivers cannot pile up overlapping runs. Cross-tenant in one pass
     * under the system context; the transaction wraps the batch (see {@code OutboxRelay} for why the
     * context wraps the transaction rather than using {@code @Transactional}).
     */
    @Scheduled(fixedDelayString = "${webhook.poll-interval-ms:5000}", initialDelay = 15_000)
    public void dispatchDue() {
        TenantContext.runAsSystem(() -> transactionTemplate.executeWithoutResult(tx -> dispatchBatch()));
    }

    private void dispatchBatch() {
        List<WebhookDelivery> batch = deliveryRepository.lockDueBatch(LocalDateTime.now(), batchSize);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("WebhookDispatcher: attempting {} due delivery(ies)", batch.size());
        for (WebhookDelivery delivery : batch) {
            attempt(delivery);
        }
    }

    /** Sends one delivery and settles its row (DELIVERED, or backoff/DEAD on failure). */
    private void attempt(WebhookDelivery delivery) {
        WebhookSubscription subscription = subscriptionRepository.findById(delivery.getSubscriptionId())
                .orElse(null);
        if (subscription == null) {
            // The subscription was deleted after this row was queued (ON DELETE CASCADE normally removes
            // the row too; this guards the race). Nothing to deliver to — retire it.
            markDead(delivery, "Subscription no longer exists");
            return;
        }

        // Phase 3.5 S2: re-validate the URL at delivery time. A name safe when the operator saved it can
        // resolve to an internal address later (DNS rebinding); re-resolving here closes that hole. An
        // unsafe URL is terminal — retrying would just re-resolve to the same blocked address.
        try {
            urlSafetyValidator.validate(subscription.getUrl());
        } catch (UrlSafetyValidator.UnsafeUrlException e) {
            markDead(delivery, "Blocked at delivery (URL resolves to a non-public address): " + e.getMessage());
            applySubscriptionHealth(subscription, delivery);
            return;
        }

        delivery.setAttempts(delivery.getAttempts() + 1);
        try {
            String secret = secretCipher.decrypt(subscription.getSecretEnc());
            long timestamp = Instant.now().getEpochSecond();
            String body = delivery.getPayload();

            ResponseEntity<Void> response = webhookWebClient.post()
                    .uri(URI.create(subscription.getUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSigner.EVENT_HEADER, delivery.getEventType())
                    .header(WebhookSigner.DELIVERY_HEADER, delivery.getEventId().toString())
                    .header(WebhookSigner.SIGNATURE_HEADER, signer.signatureHeader(secret, timestamp, body))
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(timeoutSeconds));

            int status = response != null ? response.getStatusCode().value() : 0;
            delivery.setResponseStatus(status);
            if (response != null && response.getStatusCode().is2xxSuccessful()) {
                markDelivered(delivery);
            } else {
                fail(delivery, "Non-2xx response: " + status);
            }
        } catch (WebClientResponseException e) {
            delivery.setResponseStatus(e.getStatusCode().value());
            fail(delivery, "HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            // Timeout, connection refused, DNS failure, TLS error — no HTTP status.
            fail(delivery, truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        deliveryRepository.save(delivery);
        applySubscriptionHealth(subscription, delivery);
    }

    /**
     * Phase 3.5 S2: tracks a subscription's delivery health after a row settles. A {@code DELIVERED}
     * resets the consecutive-failure run; a {@code DEAD} delivery extends it, and once the run reaches
     * {@code webhook.auto-disable-after} the endpoint is auto-disabled with a recorded reason so a
     * permanently-dead receiver stops generating doomed deliveries (and the UI can show why). A delivery
     * still in backoff ({@code PENDING}) is not yet a settled outcome → no change.
     */
    private void applySubscriptionHealth(WebhookSubscription subscription, WebhookDelivery delivery) {
        if (delivery.getStatus() == WebhookDeliveryStatus.DELIVERED) {
            if (subscription.getConsecutiveFailures() != 0) {
                subscription.setConsecutiveFailures(0);
                subscriptionRepository.save(subscription);
            }
        } else if (delivery.getStatus() == WebhookDeliveryStatus.DEAD) {
            subscription.setConsecutiveFailures(subscription.getConsecutiveFailures() + 1);
            if (subscription.isEnabled() && subscription.getConsecutiveFailures() >= autoDisableAfter) {
                subscription.setEnabled(false);
                subscription.setDisabledReason("Auto-disabled after " + subscription.getConsecutiveFailures()
                        + " consecutive failed deliveries");
                autoDisabledCounter.increment();
                log.warn("Webhook subscription id={} (tenant {}) auto-disabled after {} consecutive failures",
                        subscription.getId(), subscription.getTenantId(), subscription.getConsecutiveFailures());
            }
            subscriptionRepository.save(subscription);
        }
    }

    private void markDelivered(WebhookDelivery delivery) {
        delivery.setStatus(WebhookDeliveryStatus.DELIVERED);
        delivery.setDeliveredAt(LocalDateTime.now());
        delivery.setLastError(null);
        deliveredCounter.increment();
    }

    /** Records a failure and either reschedules with backoff or retires the row once attempts run out. */
    private void fail(WebhookDelivery delivery, String reason) {
        delivery.setLastError(truncate(reason));
        if (delivery.getAttempts() >= maxAttempts) {
            delivery.setStatus(WebhookDeliveryStatus.DEAD);
            deadCounter.increment();
            log.warn("Webhook delivery id={} DEAD after {} attempts: {}",
                    delivery.getId(), delivery.getAttempts(), reason);
        } else {
            delivery.setNextAttemptAt(LocalDateTime.now().plusSeconds(backoffSeconds(delivery.getAttempts())));
            failedCounter.increment();
            log.debug("Webhook delivery id={} failed (attempt {}): {}; retrying at {}",
                    delivery.getId(), delivery.getAttempts(), reason, delivery.getNextAttemptAt());
        }
    }

    private void markDead(WebhookDelivery delivery, String reason) {
        delivery.setStatus(WebhookDeliveryStatus.DEAD);
        delivery.setLastError(truncate(reason));
        deadCounter.increment();
        deliveryRepository.save(delivery);
    }

    /** Exponential backoff: base * 2^(attempt-1), capped. attempt is 1-based (1 = first retry). */
    private long backoffSeconds(int attempt) {
        long shift = Math.min(attempt - 1, 20); // guard against overflow on absurd attempt counts
        long delay = backoffBaseSeconds << shift;
        return Math.min(delay <= 0 ? backoffCapSeconds : delay, backoffCapSeconds);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
