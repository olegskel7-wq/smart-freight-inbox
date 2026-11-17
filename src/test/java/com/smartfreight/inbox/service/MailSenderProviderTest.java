package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.MailboxAuthType;
import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.startup.DemoMailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MailSenderProvider} — per-tenant SMTP resolution: the ticket's own
 * mailbox, then the tenant's default SMTP mailbox, then the platform account.
 *
 * <p>"Usable" (enabled + SMTP host) is filtered by the repository query, so here an unusable
 * ticket mailbox is simply an empty by-id lookup; the real filtering and tenant scoping are
 * proven on Postgres in {@code TicketMailboxTenantIsolationTest}.
 */
class MailSenderProviderTest {

    private TenantMailboxRepository mailboxes;
    private SecretCipher cipher;
    private JavaMailSender platformSender;
    private DemoMailProperties demo;
    private MailSenderProvider provider;

    @BeforeEach
    void setUp() {
        mailboxes = mock(TenantMailboxRepository.class);
        cipher = mock(SecretCipher.class);
        platformSender = mock(JavaMailSender.class);
        demo = new DemoMailProperties();
        provider = new MailSenderProvider(mailboxes, new MailboxCredentials(cipher, mock(Ms365TokenProvider.class)), new MailTlsPolicy(demo), platformSender,
                "platform@smartfreight.com");
    }

    private TenantMailbox smtpMailbox(String fromAddress) {
        return smtpMailbox(1L, "inbox@acme.co.uk", fromAddress);
    }

    private TenantMailbox smtpMailbox(Long id, String username, String fromAddress) {
        TenantMailbox m = new TenantMailbox();
        m.setId(id);
        m.setTenantId(1L);
        m.setHost("imap.gmail.com");
        m.setPort(993);
        m.setUsername(username);
        m.setPasswordEnc("ENC(pw)");
        m.setSmtpHost("smtp.gmail.com");
        m.setSmtpPort(587);
        m.setFromAddress(fromAddress);
        return m;
    }

    @Test
    void resolve_ticketMailboxUsable_winsOverTenantDefault() {
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt(any())).thenReturn("pw");
        when(mailboxes.findByIdAndEnabledTrueAndSmtpHostIsNotNull(2L))
                .thenReturn(Optional.of(smtpMailbox(2L, "quotes@acme.co.uk", "quotes@acme.co.uk")));

        MailSenderProvider.ResolvedSender resolved = provider.resolve(2L);

        assertThat(resolved.from()).isEqualTo("quotes@acme.co.uk");
        assertThat(((JavaMailSenderImpl) resolved.sender()).getUsername()).isEqualTo("quotes@acme.co.uk");
        // The oldest-mailbox rule is only a fallback — not even consulted here.
        verify(mailboxes, never()).findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc();
    }

    @Test
    void resolve_ticketMailboxUnusable_fallsBackToTenantDefault() {
        // Disabled, SMTP-less, deleted or another tenant's → the by-id lookup is empty.
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt(any())).thenReturn("pw");
        when(mailboxes.findByIdAndEnabledTrueAndSmtpHostIsNotNull(2L)).thenReturn(Optional.empty());
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc())
                .thenReturn(Optional.of(smtpMailbox(1L, "ops@acme.co.uk", "ops@acme.co.uk")));

        MailSenderProvider.ResolvedSender resolved = provider.resolve(2L);

        assertThat(resolved.from()).isEqualTo("ops@acme.co.uk");
    }

    @Test
    void resolve_ticketMailboxUnusableAndNoTenantSmtp_fallsBackToPlatform() {
        when(cipher.isConfigured()).thenReturn(true);
        when(mailboxes.findByIdAndEnabledTrueAndSmtpHostIsNotNull(2L)).thenReturn(Optional.empty());
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc()).thenReturn(Optional.empty());

        MailSenderProvider.ResolvedSender resolved = provider.resolve(2L);

        assertThat(resolved.sender()).isSameAs(platformSender);
        assertThat(resolved.from()).isEqualTo("platform@smartfreight.com");
    }

    @Test
    void resolve_noTicketMailbox_usesTenantDefaultWithoutByIdLookup() {
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt(any())).thenReturn("pw");
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc())
                .thenReturn(Optional.of(smtpMailbox("sales@acme.co.uk")));

        MailSenderProvider.ResolvedSender resolved = provider.resolve(null);

        assertThat(resolved.from()).isEqualTo("sales@acme.co.uk");
        verify(mailboxes, never()).findByIdAndEnabledTrueAndSmtpHostIsNotNull(any());
    }

    @Test
    void resolve_noTenantSmtp_fallsBackToPlatform() {
        when(cipher.isConfigured()).thenReturn(true);
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc())
                .thenReturn(Optional.empty());

        MailSenderProvider.ResolvedSender resolved = provider.resolve(null);

        assertThat(resolved.sender()).isSameAs(platformSender);
        assertThat(resolved.from()).isEqualTo("platform@smartfreight.com");
    }

    @Test
    void resolve_cipherNotConfigured_fallsBackToPlatform() {
        // The password could not be decrypted — whether that matters depends on the mailbox's
        // sign-in type (V38), so the mailbox is looked up and then refused.
        when(cipher.isConfigured()).thenReturn(false);
        when(mailboxes.findByIdAndEnabledTrueAndSmtpHostIsNotNull(2L))
                .thenReturn(Optional.of(smtpMailbox(2L, "quotes@acme.co.uk", "quotes@acme.co.uk")));

        MailSenderProvider.ResolvedSender resolved = provider.resolve(2L);

        assertThat(resolved.sender()).isSameAs(platformSender);
        verify(cipher, never()).decrypt(any());
    }

    // --- V38: Microsoft 365 (XOAUTH2) ---------------------------------------------------------

    private static TenantMailbox ms365(TenantMailbox m) {
        m.setAuthType(MailboxAuthType.MS365_OAUTH);
        m.setMsDirectoryId("dir-1");
        m.setPasswordEnc(null);
        m.setSmtpHost("smtp.office365.com");
        return m;
    }

    @Test
    void resolve_ms365Mailbox_sendsWithXoauth2_andAFreshTokenAtEverySend() {
        Ms365TokenProvider tokens = mock(Ms365TokenProvider.class);
        when(tokens.isConfigured()).thenReturn(true);
        when(tokens.accessToken("dir-1")).thenReturn("token-1", "token-2");
        MailSenderProvider oauthProvider = new MailSenderProvider(mailboxes, new MailboxCredentials(cipher, tokens),
                new MailTlsPolicy(demo), platformSender, "platform@smartfreight.com");
        when(mailboxes.findByIdAndEnabledTrueAndSmtpHostIsNotNull(3L))
                .thenReturn(Optional.of(ms365(smtpMailbox(3L, "ops@acme.co.uk", null))));

        JavaMailSenderImpl sender = (JavaMailSenderImpl) oauthProvider.resolve(3L).sender();

        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.auth.mechanisms")).isEqualTo("XOAUTH2");
        // JavaMailSenderImpl reads getPassword() at every transport connect: the cached sender
        // outlives the token, so each send must ask again.
        assertThat(sender.getPassword()).isEqualTo("token-1");
        assertThat(sender.getPassword()).isEqualTo("token-2");
        verify(cipher, never()).decrypt(any());
    }

    @Test
    void resolve_ms365MailboxWithoutEntraApp_fallsBackToPlatform() {
        when(cipher.isConfigured()).thenReturn(true);
        when(mailboxes.findByIdAndEnabledTrueAndSmtpHostIsNotNull(3L))
                .thenReturn(Optional.of(ms365(smtpMailbox(3L, "ops@acme.co.uk", null))));

        // setUp's token provider is a bare mock: isConfigured() == false, like unset MS365_CLIENT_*.
        assertThat(provider.resolve(3L).sender()).isSameAs(platformSender);
    }

    @Test
    void resolve_tenantSmtp_buildsSenderFromMailbox() {
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt("ENC(pw)")).thenReturn("pw");
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc())
                .thenReturn(Optional.of(smtpMailbox("sales@acme.co.uk")));

        MailSenderProvider.ResolvedSender resolved = provider.resolve(null);

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
    void resolve_realSmtpHost_requiresStartTlsAndVerifiesTheCertificate() {
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt("ENC(pw)")).thenReturn("pw");
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc())
                .thenReturn(Optional.of(smtpMailbox("sales@acme.co.uk")));

        Properties props = ((JavaMailSenderImpl) provider.resolve(null).sender()).getJavaMailProperties();

        assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(props.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(props).doesNotContainKey("mail.smtp.ssl.trust");
    }

    @Test
    void resolve_demoMailpitMailbox_keepsWorkingWithoutTls() {
        demo.setEnabled(true);
        TenantMailbox mailpit = smtpMailbox("ops@felixstowe-freight.example.com");
        mailpit.setSmtpHost("mailpit");
        mailpit.setSmtpPort(1025);
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt("ENC(pw)")).thenReturn("pw");
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc()).thenReturn(Optional.of(mailpit));

        Properties props = ((JavaMailSenderImpl) provider.resolve(null).sender()).getJavaMailProperties();

        assertThat(props).doesNotContainKey("mail.smtp.starttls.required");
        assertThat(props.getProperty("mail.smtp.ssl.trust")).isEqualTo("mailpit");
    }

    @Test
    void resolve_tenantSmtpWithoutFromAddress_usesUsername() {
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt("ENC(pw)")).thenReturn("pw");
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc())
                .thenReturn(Optional.of(smtpMailbox(null)));

        MailSenderProvider.ResolvedSender resolved = provider.resolve(null);

        assertThat(resolved.from()).isEqualTo("inbox@acme.co.uk");
    }

    @Test
    void resolve_sameConfig_cachesSenderInstance() {
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt("ENC(pw)")).thenReturn("pw");
        when(mailboxes.findByIdAndEnabledTrueAndSmtpHostIsNotNull(1L))
                .thenReturn(Optional.of(smtpMailbox("sales@acme.co.uk")));

        JavaMailSender first = provider.resolve(1L).sender();
        JavaMailSender second = provider.resolve(1L).sender();

        // Same fingerprint → one built sender reused (decrypt happens once at build time).
        assertThat(second).isSameAs(first);
        verify(cipher).decrypt("ENC(pw)");
    }

    @Test
    void resolve_rotatedPassword_rebuildsAndReplacesTheCachedSender() {
        TenantMailbox mailbox = smtpMailbox("sales@acme.co.uk");
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt("ENC(pw)")).thenReturn("pw");
        when(cipher.decrypt("ENC(rotated)")).thenReturn("rotated");
        when(mailboxes.findByIdAndEnabledTrueAndSmtpHostIsNotNull(1L)).thenReturn(Optional.of(mailbox));

        JavaMailSender before = provider.resolve(1L).sender();
        mailbox.setPasswordEnc("ENC(rotated)");   // edited in place on /settings (Gate B15)
        JavaMailSender after = provider.resolve(1L).sender();

        assertThat(after).isNotSameAs(before);
        assertThat(((JavaMailSenderImpl) after).getPassword()).isEqualTo("rotated");
        // The old sender (and the stale plaintext password in it) is dropped, not kept alongside.
        assertThat(provider.cachedSenderCount()).isEqualTo(1);
    }

    @Test
    void resolve_twoInboxesOfOneTenant_getDistinctSenders() {
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.decrypt(any())).thenReturn("pw");
        when(mailboxes.findByIdAndEnabledTrueAndSmtpHostIsNotNull(1L))
                .thenReturn(Optional.of(smtpMailbox(1L, "ops@acme.co.uk", null)));
        when(mailboxes.findByIdAndEnabledTrueAndSmtpHostIsNotNull(2L))
                .thenReturn(Optional.of(smtpMailbox(2L, "quotes@acme.co.uk", null)));

        MailSenderProvider.ResolvedSender ops = provider.resolve(1L);
        MailSenderProvider.ResolvedSender quotes = provider.resolve(2L);

        // Different accounts → different cached transports, each FROM its own address.
        assertThat(quotes.sender()).isNotSameAs(ops.sender());
        assertThat(ops.from()).isEqualTo("ops@acme.co.uk");
        assertThat(quotes.from()).isEqualTo("quotes@acme.co.uk");
    }
}
