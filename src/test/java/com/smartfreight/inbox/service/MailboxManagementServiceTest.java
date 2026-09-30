package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.ShipmentWatchRepository;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MailboxManagementService} — input validation and that the IMAP
 * password is encrypted before it reaches the repository (Phase 2.2 S3).
 */
class MailboxManagementServiceTest {

    private TenantMailboxRepository mailboxes;
    private EmailTicketRepository tickets;
    private ShipmentWatchRepository watches;
    private SecretCipher cipher;
    private MailboxManagementService service;

    @BeforeEach
    void setUp() {
        mailboxes = mock(TenantMailboxRepository.class);
        cipher = mock(SecretCipher.class);
        tickets = mock(EmailTicketRepository.class);
        watches = mock(ShipmentWatchRepository.class);
        service = new MailboxManagementService(mailboxes, tickets, watches, cipher);
    }

    @Test
    void create_encryptsPasswordBeforePersisting() {
        when(cipher.encrypt("s3cret")).thenReturn("ENC(s3cret)");

        service.create("imap.gmail.com", 993, "inbox@acme.co.uk", "s3cret", null, null, null);

        ArgumentCaptor<TenantMailbox> saved = ArgumentCaptor.forClass(TenantMailbox.class);
        verify(mailboxes).save(saved.capture());
        // The row carries the cipher output, not the raw password — encryption happened
        // in the service before persistence (real ciphertext is covered by SecretCipherTest).
        assertThat(saved.getValue().getPasswordEnc()).isEqualTo("ENC(s3cret)");
        assertThat(saved.getValue().isEnabled()).isTrue();
        // No SMTP given → inbound-only row, outbound falls back to the platform account.
        assertThat(saved.getValue().getSmtpHost()).isNull();
    }

    @Test
    void create_blankFields_rejectedWithoutTouchingCipherOrRepo() {
        assertThatThrownBy(() -> service.create("  ", 993, "user", "pw", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cipher, never()).encrypt(anyString());
        verify(mailboxes, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void create_portOutOfRange_rejected() {
        assertThatThrownBy(() -> service.create("imap.gmail.com", 70000, "user", "pw", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Port");
    }

    @Test
    void create_withSmtp_persistsSmtpConfig() {
        when(cipher.encrypt("s3cret")).thenReturn("ENC(s3cret)");

        service.create("imap.gmail.com", 993, "inbox@acme.co.uk", "s3cret",
                "smtp.gmail.com", 587, "sales@acme.co.uk");

        ArgumentCaptor<TenantMailbox> saved = ArgumentCaptor.forClass(TenantMailbox.class);
        verify(mailboxes).save(saved.capture());
        assertThat(saved.getValue().getSmtpHost()).isEqualTo("smtp.gmail.com");
        assertThat(saved.getValue().getSmtpPort()).isEqualTo(587);
        assertThat(saved.getValue().getFromAddress()).isEqualTo("sales@acme.co.uk");
    }

    @Test
    void create_smtpHostWithoutPort_defaultsTo587() {
        when(cipher.encrypt("s3cret")).thenReturn("ENC(s3cret)");

        service.create("imap.gmail.com", 993, "inbox@acme.co.uk", "s3cret",
                "smtp.gmail.com", null, null);

        ArgumentCaptor<TenantMailbox> saved = ArgumentCaptor.forClass(TenantMailbox.class);
        verify(mailboxes).save(saved.capture());
        assertThat(saved.getValue().getSmtpPort()).isEqualTo(587);
        // Blank from-address → null, so the sender uses the username.
        assertThat(saved.getValue().getFromAddress()).isNull();
    }

    // ---- update (Gate B15: edit in place, the id — and every ticket's mailbox_id — survives) ----

    private TenantMailbox existing() {
        TenantMailbox m = new TenantMailbox();
        m.setId(7L);
        m.setHost("imap.gmail.com");
        m.setPort(993);
        m.setUsername("inbox@acme.co.uk");
        m.setPasswordEnc("ENC(old)");
        m.setSmtpHost("smtp.gmail.com");
        m.setSmtpPort(587);
        m.setFromAddress("sales@acme.co.uk");
        m.setEnabled(false);
        when(mailboxes.findById(7L)).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    void update_newPassword_reEncryptsOnTheSameRow() {
        TenantMailbox m = existing();
        when(cipher.encrypt("rotated")).thenReturn("ENC(rotated)");

        service.update(7L, " imap.gmail.com ", 993, "inbox@acme.co.uk", "rotated",
                "smtp.gmail.com", 465, "quotes@acme.co.uk");

        verify(mailboxes).saveAndFlush(m);
        verify(mailboxes, never()).delete(any());
        assertThat(m.getId()).isEqualTo(7L);
        assertThat(m.getPasswordEnc()).isEqualTo("ENC(rotated)");
        assertThat(m.getHost()).isEqualTo("imap.gmail.com");
        assertThat(m.getSmtpPort()).isEqualTo(465);
        assertThat(m.getFromAddress()).isEqualTo("quotes@acme.co.uk");
        // Editing is not un-pausing — the enabled flag belongs to toggle.
        assertThat(m.isEnabled()).isFalse();
    }

    @Test
    void update_blankPassword_keepsTheStoredCiphertext() {
        TenantMailbox m = existing();

        service.update(7L, "imap.gmail.com", 993, "inbox@acme.co.uk", "  ",
                "smtp.gmail.com", 587, "sales@acme.co.uk");

        assertThat(m.getPasswordEnc()).isEqualTo("ENC(old)");
        verify(cipher, never()).encrypt(anyString());
        verify(mailboxes).saveAndFlush(m);
    }

    @Test
    void update_blankSmtpHost_makesTheMailboxInboundOnly() {
        TenantMailbox m = existing();

        service.update(7L, "imap.gmail.com", 993, "inbox@acme.co.uk", null, "", 587, "ignored@acme.co.uk");

        assertThat(m.getSmtpHost()).isNull();
        assertThat(m.getSmtpPort()).isNull();
        assertThat(m.getFromAddress()).isNull();
    }

    // Re-pointing without the password would let a caller who never knew it redirect the stored
    // credential to their own server (security review of B15) — every such change needs it re-entered.

    @Test
    void update_imapHostChangedWithoutPassword_rejected() {
        TenantMailbox m = existing();

        assertThatThrownBy(() -> service.update(7L, "imap.attacker.tld", 993, "inbox@acme.co.uk", "",
                "smtp.gmail.com", 587, "sales@acme.co.uk"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Re-enter the password");
        verify(mailboxes, never()).saveAndFlush(any());
        assertThat(m.getPasswordEnc()).isEqualTo("ENC(old)");
    }

    @Test
    void update_imapPortOrUsernameChangedWithoutPassword_rejected() {
        existing();

        assertThatThrownBy(() -> service.update(7L, "imap.gmail.com", 143, "inbox@acme.co.uk", null,
                "smtp.gmail.com", 587, null))
                .hasMessageContaining("Re-enter the password");
        assertThatThrownBy(() -> service.update(7L, "imap.gmail.com", 993, "other@acme.co.uk", null,
                "smtp.gmail.com", 587, null))
                .hasMessageContaining("Re-enter the password");
        verify(mailboxes, never()).saveAndFlush(any());
    }

    @Test
    void update_smtpRepointedWithoutPassword_rejected() {
        existing();

        assertThatThrownBy(() -> service.update(7L, "imap.gmail.com", 993, "inbox@acme.co.uk", "",
                "smtp.attacker.tld", 587, null))
                .hasMessageContaining("Re-enter the password");
        assertThatThrownBy(() -> service.update(7L, "imap.gmail.com", 993, "inbox@acme.co.uk", "",
                "smtp.gmail.com", 25, null))
                .hasMessageContaining("Re-enter the password");
        verify(mailboxes, never()).saveAndFlush(any());
    }

    @Test
    void update_smtpAddedToInboundOnlyMailboxWithoutPassword_rejected() {
        TenantMailbox m = existing();
        m.setSmtpHost(null);
        m.setSmtpPort(null);

        assertThatThrownBy(() -> service.update(7L, "imap.gmail.com", 993, "inbox@acme.co.uk", "",
                "smtp.gmail.com", 587, null))
                .hasMessageContaining("Re-enter the password");
    }

    @Test
    void update_repointedWithNewPassword_allowed() {
        TenantMailbox m = existing();
        when(cipher.encrypt("new-pw")).thenReturn("ENC(new-pw)");

        service.update(7L, "outlook.office365.com", 993, "inbox@acme.co.uk", "new-pw",
                "smtp.office365.com", 587, null);

        assertThat(m.getHost()).isEqualTo("outlook.office365.com");
        assertThat(m.getPasswordEnc()).isEqualTo("ENC(new-pw)");
        verify(mailboxes).saveAndFlush(m);
    }

    @Test
    void update_fromAddressOnlyOrTrimmedSameHost_needsNoPassword() {
        TenantMailbox m = existing();

        service.update(7L, " imap.gmail.com ", 993, " inbox@acme.co.uk ", "",
                "smtp.gmail.com", null, "quotes@acme.co.uk");

        // Trim + the 587 default normalise back to the stored endpoint — not a re-point.
        assertThat(m.getFromAddress()).isEqualTo("quotes@acme.co.uk");
        verify(mailboxes).saveAndFlush(m);
    }

    // The username IS the account: once mail arrived on it, renaming would make its customers get
    // replies from a different address — a new address is a new mailbox.

    @Test
    void update_renamingAMailboxWithTickets_rejectedAsInUse() {
        TenantMailbox m = existing();
        when(tickets.existsByMailboxId(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(7L, "imap.gmail.com", 993, "sales@other.co.uk", "pw",
                "smtp.gmail.com", 587, null))
                .isInstanceOf(MailboxManagementService.MailboxInUseException.class);
        verify(mailboxes, never()).saveAndFlush(any());
        verify(cipher, never()).encrypt(anyString());
        assertThat(m.getPasswordEnc()).isEqualTo("ENC(old)");
    }

    @Test
    void update_renamingAMailboxWithOnlyAStatusWatch_rejectedAsInUse() {
        existing();
        when(watches.existsByMailboxId(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(7L, "imap.gmail.com", 993, "sales@other.co.uk", "pw",
                "smtp.gmail.com", 587, null))
                .isInstanceOf(MailboxManagementService.MailboxInUseException.class);
    }

    @Test
    void update_fixingAUsernameTypoBeforeAnyMail_allowedWithThePassword() {
        TenantMailbox m = existing();
        when(cipher.encrypt("pw")).thenReturn("ENC(pw)");

        service.update(7L, "imap.gmail.com", 993, "orders@acme.co.uk", "pw",
                "smtp.gmail.com", 587, null);

        assertThat(m.getUsername()).isEqualTo("orders@acme.co.uk");
        verify(mailboxes).saveAndFlush(m);
    }

    @Test
    void update_caseOnlyChanges_areNeitherARenameNorARepoint() {
        TenantMailbox m = existing();
        when(tickets.existsByMailboxId(7L)).thenReturn(true);

        service.update(7L, "IMAP.gmail.com", 993, "Inbox@Acme.co.uk", "",
                "SMTP.gmail.com", 587, "sales@acme.co.uk");

        assertThat(m.getUsername()).isEqualTo("Inbox@Acme.co.uk");
        assertThat(m.getPasswordEnc()).isEqualTo("ENC(old)");
        verify(mailboxes).saveAndFlush(m);
    }

    @Test
    void update_unknownId_isNotFound() {
        when(mailboxes.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, "imap.gmail.com", 993, "u", "pw", null, null, null))
                .isInstanceOf(MailboxManagementService.MailboxNotFoundException.class);
        verify(mailboxes, never()).saveAndFlush(any());
    }

    @Test
    void update_invalidInput_rejectedWithoutSaving() {
        TenantMailbox m = existing();

        assertThatThrownBy(() -> service.update(7L, "imap.gmail.com", 0, "inbox@acme.co.uk", "pw",
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(MailboxManagementService.MailboxNotFoundException.class)
                .hasMessageContaining("Port");
        assertThatThrownBy(() -> service.update(7L, "imap.gmail.com", 993, " ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cipher, never()).encrypt(anyString());
        verify(mailboxes, never()).saveAndFlush(any());
        assertThat(m.getPasswordEnc()).isEqualTo("ENC(old)");
    }

    @Test
    void create_smtpPortOutOfRange_rejected() {
        assertThatThrownBy(() -> service.create("imap.gmail.com", 993, "user", "pw",
                "smtp.gmail.com", 70000, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SMTP port");
    }
}
