package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.startup.DemoMailProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GmailDraftService} — the draft goes only into the ticket's own mailbox
 * (never a platform account) and its IMAP session gets the same verified TLS as every mail path.
 * Tenant scoping of the mailbox lookup is proven on Postgres in {@code TicketMailboxTenantIsolationTest}.
 */
class GmailDraftServiceTest {

    private SettingsService settings;
    private TenantMailboxRepository mailboxes;
    private SecretCipher cipher;
    private GmailDraftService service;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        mailboxes = mock(TenantMailboxRepository.class);
        cipher = mock(SecretCipher.class);
        when(settings.isDraftEnabled()).thenReturn(true);
        when(cipher.isConfigured()).thenReturn(true);
        service = new GmailDraftService(settings, mailboxes, cipher,
                new MailTlsPolicy(new DemoMailProperties()), new SimpleMeterRegistry());
    }

    private static EmailTicket ticket(Long mailboxId) {
        EmailTicket ticket = new EmailTicket();
        ticket.setId(116L);
        ticket.setMailboxId(mailboxId);
        return ticket;
    }

    @Test
    void imapProperties_verifyTheServerCertificate() {
        Properties p = service.buildImapProperties("imap.gmail.com", 993);

        assertThat(p.getProperty("mail.imaps.ssl.enable")).isEqualTo("true");
        assertThat(p.getProperty("mail.imaps.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p).doesNotContainKey("mail.imaps.ssl.trust");
    }

    @Test
    void saveDraft_ticketWithoutMailbox_savesNothingAndHasNoPlatformFallback() {
        service.saveDraft(ticket(null));

        verifyNoInteractions(mailboxes);
        verify(cipher, never()).decrypt(any());
    }

    @Test
    void saveDraft_mailboxNotVisibleOrDisabled_savesNothing() {
        // Another tenant's id or a disabled row both resolve to empty under the tenant filter.
        when(mailboxes.findByIdAndEnabledTrue(7L)).thenReturn(Optional.empty());

        service.saveDraft(ticket(7L));

        verify(cipher, never()).decrypt(any());
    }

    @Test
    void saveDraft_disabledSetting_skipsBeforeAnyLookup() {
        when(settings.isDraftEnabled()).thenReturn(false);

        service.saveDraft(ticket(7L));

        verifyNoInteractions(mailboxes, cipher);
    }
}
