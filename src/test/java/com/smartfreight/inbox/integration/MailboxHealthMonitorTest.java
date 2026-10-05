package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.service.SecretCipher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.AuthenticationFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MailboxHealthMonitor} (Gate B4/B13) — the login itself is stubbed; what is
 * pinned here is the verdict per mailbox, the tenant-labelled metrics the MailboxDown alert reads,
 * the masked error for /settings, and that removed mailboxes leave the metrics.
 */
class MailboxHealthMonitorTest {

    private TenantMailboxRepository mailboxes;
    private SecretCipher cipher;
    private SimpleMeterRegistry meters;
    private final Set<Long> failing = new HashSet<>();
    private MailboxHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        mailboxes = mock(TenantMailboxRepository.class);
        cipher = mock(SecretCipher.class);
        when(cipher.isConfigured()).thenReturn(true);
        meters = new SimpleMeterRegistry();
        monitor = new MailboxHealthMonitor(mailboxes, cipher, mock(MailboxImapAdapterManager.class), meters, 300_000) {
            @Override
            void login(TenantMailbox mailbox) throws Exception {
                if (failing.contains(mailbox.getId())) {
                    throw new AuthenticationFailedException("[AUTHENTICATIONFAILED] Invalid credentials for ops@a.test");
                }
            }
        };
    }

    private static TenantMailbox mailbox(long id, long tenant) {
        TenantMailbox m = new TenantMailbox();
        m.setId(id);
        m.setTenantId(tenant);
        return m;
    }

    private double gauge(String name, long tenant, long mailbox) {
        return meters.get(name).tags("tenant", String.valueOf(tenant), "mailbox", String.valueOf(mailbox)).gauge().value();
    }

    @Test
    void probe_recordsUpAndDownPerMailbox_withTenantLabels() {
        when(mailboxes.findByEnabledTrue()).thenReturn(List.of(mailbox(5, 1), mailbox(6, 90001)));
        failing.add(6L);

        monitor.probeAll();

        assertThat(gauge("smartfreight_mailbox_up", 1, 5)).isEqualTo(1.0);
        assertThat(gauge("smartfreight_mailbox_up", 90001, 6)).isEqualTo(0.0);
        assertThat(gauge("smartfreight_mailbox_last_success_timestamp_seconds", 1, 5)).isPositive();
        // Never logged in → no last-success series for it (the alert keys on _up, not on this).
        assertThat(meters.find("smartfreight_mailbox_last_success_timestamp_seconds").tag("mailbox", "6").gauge()).isNull();

        MailboxHealthMonitor.MailboxHealth down = monitor.health(6L).orElseThrow();
        assertThat(down.up()).isFalse();
        assertThat(down.lastSuccessAt()).isNull();
        assertThat(down.lastError()).contains("AUTHENTICATIONFAILED").contains("***@a.test").doesNotContain("ops@");
    }

    @Test
    void failureAfterSuccess_keepsTheLastGoodLoginTime_andRecoveryClearsTheError() {
        when(mailboxes.findByEnabledTrue()).thenReturn(List.of(mailbox(5, 1)));
        monitor.probeAll();
        var firstOk = monitor.health(5L).orElseThrow().lastSuccessAt();

        failing.add(5L);
        monitor.probeAll();
        assertThat(monitor.health(5L).orElseThrow().up()).isFalse();
        assertThat(monitor.health(5L).orElseThrow().lastSuccessAt()).isEqualTo(firstOk);

        failing.clear();
        monitor.probeAll();
        assertThat(monitor.health(5L).orElseThrow().up()).isTrue();
        assertThat(monitor.health(5L).orElseThrow().lastError()).isNull();
    }

    @Test
    void removedMailbox_leavesHealthAndMetrics() {
        when(mailboxes.findByEnabledTrue()).thenReturn(List.of(mailbox(5, 1), mailbox(6, 1)));
        monitor.probeAll();

        when(mailboxes.findByEnabledTrue()).thenReturn(List.of(mailbox(5, 1)));
        monitor.probeAll();

        assertThat(monitor.health(6L)).isEmpty();
        assertThat(meters.find("smartfreight_mailbox_up").tag("mailbox", "6").gauge()).isNull();
    }

    @Test
    void cipherNotConfigured_probesNothing() {
        when(cipher.isConfigured()).thenReturn(false);

        monitor.probeAll();

        verify(mailboxes, never()).findByEnabledTrue();
    }
}
