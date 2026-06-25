package com.smartfreight.inbox.webhook;

import com.smartfreight.inbox.entity.WebhookDelivery;
import com.smartfreight.inbox.entity.WebhookDeliveryStatus;
import com.smartfreight.inbox.entity.WebhookSubscription;
import com.smartfreight.inbox.repository.WebhookDeliveryRepository;
import com.smartfreight.inbox.repository.WebhookSubscriptionRepository;
import com.smartfreight.inbox.service.SecretCipher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the real {@link WebhookDispatcher} HTTP path against a scripted endpoint ({@link MockWebServer}),
 * with mocked repositories. Verifies the success / retry-with-backoff / dead-after-budget transitions and
 * that each request carries the signature + event + delivery headers (Phase 3.5).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookDispatcherTest {

    @Mock private WebhookDeliveryRepository deliveryRepository;
    @Mock private WebhookSubscriptionRepository subscriptionRepository;
    // A mock validator permits every URL by default (void validate is a no-op) so the HTTP-path tests can
    // target the loopback MockWebServer; the SSRF re-check test stubs it to throw.
    @Mock private UrlSafetyValidator urlSafetyValidator;

    private MockWebServer server;
    private SecretCipher cipher;
    private WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(mock(PlatformTransactionManager.class));

        dispatcher = new WebhookDispatcher(
                deliveryRepository, subscriptionRepository, cipher, new WebhookSigner(), urlSafetyValidator,
                WebClient.builder().build(), transactionTemplate, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(dispatcher, "batchSize", 20);
        ReflectionTestUtils.setField(dispatcher, "maxAttempts", 8);
        ReflectionTestUtils.setField(dispatcher, "backoffBaseSeconds", 60L);
        ReflectionTestUtils.setField(dispatcher, "backoffCapSeconds", 21600L);
        ReflectionTestUtils.setField(dispatcher, "timeoutSeconds", 5L);
        ReflectionTestUtils.setField(dispatcher, "autoDisableAfter", 3);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private WebhookSubscription subscription() {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setId(1L);
        sub.setTenantId(1L);
        sub.setUrl(server.url("/hook").toString());
        sub.setSecretEnc(cipher.encrypt("topsecret"));
        return sub;
    }

    private WebhookDelivery pendingDelivery() {
        WebhookDelivery d = new WebhookDelivery();
        d.setId(10L);
        d.setTenantId(1L);
        d.setSubscriptionId(1L);
        d.setEventId(UUID.randomUUID());
        d.setEventType("ticket.created");
        d.setPayload("{\"id\":\"x\",\"type\":\"ticket.created\"}");
        d.setStatus(WebhookDeliveryStatus.PENDING);
        d.setNextAttemptAt(LocalDateTime.now());
        return d;
    }

    @Test
    void successMarksDeliveredWithHeaders() throws Exception {
        WebhookDelivery delivery = pendingDelivery();
        when(deliveryRepository.lockDueBatch(any(), anyInt())).thenReturn(List.of(delivery));
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(subscription()));
        server.enqueue(new MockResponse().setResponseCode(200));

        dispatcher.dispatchDue();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivery.getResponseStatus()).isEqualTo(200);
        assertThat(delivery.getDeliveredAt()).isNotNull();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("X-SmartFreight-Event")).isEqualTo("ticket.created");
        assertThat(request.getHeader("X-SmartFreight-Delivery")).isEqualTo(delivery.getEventId().toString());
        assertThat(request.getHeader("X-SmartFreight-Signature")).contains("t=").contains("v1=");
        assertThat(request.getBody().readUtf8()).isEqualTo(delivery.getPayload());
    }

    @Test
    void non2xxReschedulesWithBackoff() {
        WebhookDelivery delivery = pendingDelivery();
        when(deliveryRepository.lockDueBatch(any(), anyInt())).thenReturn(List.of(delivery));
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(subscription()));
        server.enqueue(new MockResponse().setResponseCode(500));

        dispatcher.dispatchDue();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isAfter(LocalDateTime.now());
        assertThat(delivery.getLastError()).contains("500");
    }

    @Test
    void deadAfterExhaustingAttempts() {
        WebhookDelivery delivery = pendingDelivery();
        delivery.setAttempts(7); // one short of the budget of 8
        when(deliveryRepository.lockDueBatch(any(), anyInt())).thenReturn(List.of(delivery));
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(subscription()));
        server.enqueue(new MockResponse().setResponseCode(503));

        dispatcher.dispatchDue();

        assertThat(delivery.getAttempts()).isEqualTo(8);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
    }

    @Test
    void missingSubscriptionRetiresDelivery() {
        WebhookDelivery delivery = pendingDelivery();
        when(deliveryRepository.lockDueBatch(any(), anyInt())).thenReturn(List.of(delivery));
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.empty());

        dispatcher.dispatchDue();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(server.getRequestCount()).isZero();
    }

    // ---- Phase 3.5 S2: runtime SSRF re-check + auto-disable -------------------------------

    @Test
    void unsafeUrlAtDeliveryTimeIsTerminalDead() {
        WebhookDelivery delivery = pendingDelivery();
        WebhookSubscription sub = subscription();
        when(deliveryRepository.lockDueBatch(any(), anyInt())).thenReturn(List.of(delivery));
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        // The URL was safe at save time but now resolves to a private address (DNS rebinding).
        org.mockito.Mockito.doThrow(new UrlSafetyValidator.UnsafeUrlException("resolves to a non-public address"))
                .when(urlSafetyValidator).validate(sub.getUrl());

        dispatcher.dispatchDue();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(delivery.getLastError()).contains("Blocked at delivery");
        assertThat(server.getRequestCount()).isZero(); // never connected
        assertThat(sub.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void deadDeliveryAutoDisablesSubscriptionAtThreshold() {
        WebhookDelivery delivery = pendingDelivery();
        delivery.setAttempts(7); // next 503 exhausts the budget → DEAD
        WebhookSubscription sub = subscription();
        sub.setConsecutiveFailures(2); // one short of the auto-disable threshold (3)
        when(deliveryRepository.lockDueBatch(any(), anyInt())).thenReturn(List.of(delivery));
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        server.enqueue(new MockResponse().setResponseCode(503));

        dispatcher.dispatchDue();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(sub.getConsecutiveFailures()).isEqualTo(3);
        assertThat(sub.isEnabled()).isFalse();
        assertThat(sub.getDisabledReason()).contains("Auto-disabled");
    }

    @Test
    void successfulDeliveryResetsConsecutiveFailures() {
        WebhookDelivery delivery = pendingDelivery();
        WebhookSubscription sub = subscription();
        sub.setConsecutiveFailures(2);
        when(deliveryRepository.lockDueBatch(any(), anyInt())).thenReturn(List.of(delivery));
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        server.enqueue(new MockResponse().setResponseCode(200));

        dispatcher.dispatchDue();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(sub.getConsecutiveFailures()).isZero();
        assertThat(sub.isEnabled()).isTrue();
    }
}
