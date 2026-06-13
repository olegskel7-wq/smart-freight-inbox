package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MailSenderProvider} — per-tenant SMTP resolution with platform
 * fallback (Phase 2.2 SMTP close-out).
 */
class MailSenderProviderTest {

    private TenantMailboxRepository mailboxes;
    private SecretCipher cipher;
    private JavaMailSender platformSender;
    private MailSenderProvider provider;

    @BeforeEach
    void setUp() {
        mailboxes = mock(TenantMailboxRepository.class);
        cipher = mock(SecretCipher.class);
        platformSender = mock(JavaMailSender.class);
        provider = new MailSenderProvider(mailboxes, cipher, platformSender, "platform@smartfreight.com");
    }

    private TenantMailbox smtpMailbox(String fromAddress) {
        TenantMailbox m = new TenantMailbox();
        m.setTenantId(1L);
        m.setHost("imap.gmail.com");
        m.setPort(993);
        m.setUsername("inbox@acme.co.uk");
        m.setPasswordEnc("ENC(pw)");
        m.setSmtpHost("smtp.gmail.com");
        m.setSmtpPort(587);
        m.setFromAddress(fromAddress);
        return m;
    }

    @Test
    void resolve_noTenantSmtp_fallsBackToPlatform() {
        when(cipher.isConfigured()).thenReturn(true);
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc())
                .thenReturn(Optional.empty());

        MailSenderProvider.ResolvedSender resolved = provider.resolve();

        assertThat(resolved.sender()).isSameAs(platformSender);
        assertThat(resolved.from()).isEqualTo("platform@smartfreight.com");
    }

    @Test
    void resolve_cipherNotConfigured_fallsBackWithoutQueryingMailboxes() {
        when(cipher.isConfigured()).thenReturn(false);

        MailSenderProvider.ResolvedSender resolved = provider.resolve();

        assertThat(resolved.sender()).isSameAs(platformSender);
        // No point reading mailboxes — the password could not be decrypted anyway.
        verify(mailboxes, never()).findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc();
    }

    @Test
    void resolve_tenantSmtp_buildsSenderFromMailbox() {
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt("ENC(pw)")).thenReturn("pw");
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc())
                .thenReturn(Optional.of(smtpMailbox("sales@acme.co.uk")));

        MailSenderProvider.ResolvedSender resolved = provider.resolve();

        assertThat(resolved.sender()).isNotSameAs(platformSender);
        assertThat(resolved.sender()).isInstanceOf(JavaMailSenderImpl.class);
        JavaMailSenderImpl built = (JavaMailSenderImpl) resolved.sender();
        assertThat(built.getHost()).isEqualTo("smtp.gmail.com");
        assertThat(built.getPort()).isEqualTo(587);
        assertThat(built.getUsername()).isEqualTo("inbox@acme.co.uk");
        assertThat(built.getPassword()).isEqualTo("pw");
        assertThat(resolved.from()).isEqualTo("sales@acme.co.uk");
    }

    @Test
    void resolve_tenantSmtpWithoutFromAddress_usesUsername() {
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt("ENC(pw)")).thenReturn("pw");
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc())
                .thenReturn(Optional.of(smtpMailbox(null)));

        MailSenderProvider.ResolvedSender resolved = provider.resolve();

        assertThat(resolved.from()).isEqualTo("inbox@acme.co.uk");
    }

    @Test
    void resolve_sameConfig_cachesSenderInstance() {
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt("ENC(pw)")).thenReturn("pw");
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc())
                .thenReturn(Optional.of(smtpMailbox("sales@acme.co.uk")));

        JavaMailSender first = provider.resolve().sender();
        JavaMailSender second = provider.resolve().sender();

        // Same fingerprint → one built sender reused (decrypt happens once at build time).
        assertThat(second).isSameAs(first);
        verify(cipher).decrypt("ENC(pw)");
    }
}
