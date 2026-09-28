package com.smartfreight.inbox.startup;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.service.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DemoMailboxBootstrap} — the demo harness upserts encrypted demo
 * mailboxes when on, disables them when off, and never touches tenants that don't exist
 * (Phase 4.8 S2).
 */
class DemoMailboxBootstrapTest {

    private static final String ADDRESS = "ops@felixstowe-freight.example.com";

    private DemoMailProperties properties;
    private TenantMailboxRepository mailboxes;
    private TenantRepository tenants;
    private SecretCipher cipher;
    private DemoMailboxBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        properties = new DemoMailProperties();
        properties.setInboxes(Map.of(1L, ADDRESS));
        mailboxes = mock(TenantMailboxRepository.class);
        tenants = mock(TenantRepository.class);
        cipher = mock(SecretCipher.class);
        bootstrap = new DemoMailboxBootstrap(properties, mailboxes, tenants, cipher);

        when(tenants.existsById(1L)).thenReturn(true);
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.encrypt("demo")).thenReturn("ENC(demo)");
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc()).thenReturn(Optional.empty());
    }

    @Test
    void enabled_createsEncryptedMailboxPointingAtFakeServers() {
        properties.setEnabled(true);
        when(mailboxes.findFirstByUsernameOrderByIdAsc(ADDRESS)).thenReturn(Optional.empty());

        bootstrap.run(null);

        TenantMailbox saved = captureSaved();
        assertThat(saved.getHost()).isEqualTo("greenmail");
        assertThat(saved.getPort()).isEqualTo(3993);
        assertThat(saved.getUsername()).isEqualTo(ADDRESS);
        assertThat(saved.getPasswordEnc()).isEqualTo("ENC(demo)");
        // Replies go to Mailpit, FROM the demo tenant's own address.
        assertThat(saved.getSmtpHost()).isEqualTo("mailpit");
        assertThat(saved.getSmtpPort()).isEqualTo(1025);
        assertThat(saved.getFromAddress()).isEqualTo(ADDRESS);
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void enabled_existingRow_rewritesHostButKeepsCiphertextWhenPasswordUnchanged() {
        properties.setEnabled(true);
        properties.setImapHost("localhost");
        TenantMailbox existing = demoRow(true);
        existing.setPasswordEnc("ENC(old-iv)");
        when(mailboxes.findFirstByUsernameOrderByIdAsc(ADDRESS)).thenReturn(Optional.of(existing));
        when(cipher.decrypt("ENC(old-iv)")).thenReturn("demo");

        bootstrap.run(null);

        TenantMailbox saved = captureSaved();
        assertThat(saved.getHost()).isEqualTo("localhost");
        // Same password → same ciphertext → unchanged fingerprint, no adapter rebuild.
        assertThat(saved.getPasswordEnc()).isEqualTo("ENC(old-iv)");
        verify(cipher, never()).encrypt(anyString());
    }

    @Test
    void enabled_rowUnderRotatedKey_isReEncrypted() {
        properties.setEnabled(true);
        TenantMailbox existing = demoRow(true);
        existing.setPasswordEnc("ENC(old-key)");
        when(mailboxes.findFirstByUsernameOrderByIdAsc(ADDRESS)).thenReturn(Optional.of(existing));
        when(cipher.decrypt("ENC(old-key)")).thenThrow(new IllegalStateException("bad tag"));

        bootstrap.run(null);

        assertThat(captureSaved().getPasswordEnc()).isEqualTo("ENC(demo)");
    }

    @Test
    void enabled_missingTenant_skipped() {
        properties.setEnabled(true);
        when(tenants.existsById(1L)).thenReturn(false);

        bootstrap.run(null);

        verify(mailboxes, never()).save(any());
    }

    @Test
    void enabled_withoutEncryptionKey_storesNothing() {
        properties.setEnabled(true);
        when(cipher.isConfigured()).thenReturn(false);

        bootstrap.run(null);

        verify(mailboxes, never()).save(any());
    }

    @Test
    void disabled_disablesPreviouslyCreatedDemoMailbox() {
        TenantMailbox existing = demoRow(true);
        when(mailboxes.findFirstByUsernameOrderByIdAsc(ADDRESS)).thenReturn(Optional.of(existing));

        bootstrap.run(null);

        // Left enabled, it would stay the tenant's SMTP sender and route real replies to Mailpit.
        assertThat(captureSaved().isEnabled()).isFalse();
    }

    @Test
    void disabled_noDemoMailbox_isNoOp() {
        when(mailboxes.findFirstByUsernameOrderByIdAsc(ADDRESS)).thenReturn(Optional.empty());

        bootstrap.run(null);

        verify(mailboxes, never()).save(any());
        verify(cipher, never()).encrypt(anyString());
    }

    private TenantMailbox demoRow(boolean enabled) {
        TenantMailbox row = new TenantMailbox();
        row.setId(7L);
        row.setTenantId(1L);
        row.setHost("greenmail");
        row.setUsername(ADDRESS);
        row.setPasswordEnc("ENC(demo)");
        row.setEnabled(enabled);
        return row;
    }

    private TenantMailbox captureSaved() {
        ArgumentCaptor<TenantMailbox> saved = ArgumentCaptor.forClass(TenantMailbox.class);
        verify(mailboxes).save(saved.capture());
        return saved.getValue();
    }
}
