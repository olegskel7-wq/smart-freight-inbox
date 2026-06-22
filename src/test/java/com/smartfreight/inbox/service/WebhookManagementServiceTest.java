package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.WebhookSubscription;
import com.smartfreight.inbox.repository.WebhookDeliveryRepository;
import com.smartfreight.inbox.repository.WebhookSubscriptionRepository;
import com.smartfreight.inbox.webhook.UrlSafetyValidator;
import com.smartfreight.inbox.webhook.WebhookEventPublisher;
import com.smartfreight.inbox.webhook.WebhookEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebhookManagementService} (Phase 3.5). Uses a REAL {@link SecretCipher} (so
 * encryption + the "secret generated and returned once" contract are genuinely exercised) and a real
 * {@link UrlSafetyValidator} driven with IP-literal URLs (no DNS, offline-safe); repositories and the
 * publisher are mocked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookManagementServiceTest {

    @Mock private WebhookSubscriptionRepository subscriptionRepository;
    @Mock private WebhookDeliveryRepository deliveryRepository;
    @Mock private WebhookEventPublisher webhookEventPublisher;

    private SecretCipher cipher;
    private WebhookManagementService service;

    @BeforeEach
    void setUp() {
        // A valid 32-byte AES key (all zeros) — enough to exercise encrypt/decrypt deterministically.
        cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        service = new WebhookManagementService(
                subscriptionRepository, deliveryRepository, cipher,
                new UrlSafetyValidator(), webhookEventPublisher);
        when(subscriptionRepository.save(any(WebhookSubscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createGeneratesSecretEncryptsItAndReturnsItOnce() {
        WebhookManagementService.Created created =
                service.create("https://8.8.8.8/hook", List.of("*"), "Zapier", null);

        // The cleartext secret is returned once and looks generated.
        assertThat(created.secret()).startsWith("whsec_");
        WebhookSubscription saved = created.subscription();
        // What is stored is the ciphertext, not the cleartext, and it round-trips.
        assertThat(saved.getSecretEnc()).isNotEqualTo(created.secret());
        assertThat(cipher.decrypt(saved.getSecretEnc())).isEqualTo(created.secret());
        assertThat(saved.getEventTypes()).isEqualTo("*");
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void createHonoursAProvidedSecret() {
        WebhookManagementService.Created created =
                service.create("https://8.8.8.8/hook", List.of(WebhookEventType.TICKET_CREATED),
                        null, "my-own-secret");

        assertThat(created.secret()).isEqualTo("my-own-secret");
        assertThat(created.subscription().getEventTypes()).isEqualTo("ticket.created");
    }

    @Test
    void createRejectsNonHttpsUrl() {
        assertThatThrownBy(() -> service.create("http://8.8.8.8/hook", List.of("*"), null, null))
                .isInstanceOf(UrlSafetyValidator.UnsafeUrlException.class);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void createRejectsUnknownEventType() {
        assertThatThrownBy(() -> service.create("https://8.8.8.8/hook", List.of("ticket.exploded"), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event type");
    }

    @Test
    void createFailsWhenCipherNotConfigured() {
        WebhookManagementService noKey = new WebhookManagementService(
                subscriptionRepository, deliveryRepository, new SecretCipher(""),
                new UrlSafetyValidator(), webhookEventPublisher);

        assertThatThrownBy(() -> noKey.create("https://8.8.8.8/hook", List.of("*"), null, null))
                .isInstanceOf(IllegalStateException.class);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void updateUnknownIdThrows() {
        when(subscriptionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, "https://8.8.8.8/hook", List.of("*"), null, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void updateRotatesSecretOnlyWhenProvided() {
        WebhookSubscription existing = new WebhookSubscription();
        existing.setId(7L);
        existing.setSecretEnc(cipher.encrypt("original"));
        when(subscriptionRepository.findById(7L)).thenReturn(Optional.of(existing));

        // No new secret → keep the original.
        service.update(7L, "https://8.8.8.8/hook", List.of("*"), null, false, null);
        assertThat(cipher.decrypt(existing.getSecretEnc())).isEqualTo("original");
        assertThat(existing.isEnabled()).isFalse();

        // New secret → rotate.
        service.update(7L, "https://8.8.8.8/hook", List.of("*"), null, true, "rotated");
        assertThat(cipher.decrypt(existing.getSecretEnc())).isEqualTo("rotated");
    }

    @Test
    void sendTestQueuesToTheSubscription() {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setId(3L);
        sub.setTenantId(1L);
        when(subscriptionRepository.findById(3L)).thenReturn(Optional.of(sub));

        service.sendTest(3L);

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        verify(webhookEventPublisher).publishTo(any(WebhookSubscription.class), type.capture(), any());
        assertThat(type.getValue()).isEqualTo("ping");
    }

    @Test
    void sendTestUnknownIdThrows() {
        when(subscriptionRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendTest(404L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(webhookEventPublisher, never()).publishTo(any(), any(), any());
    }
}
