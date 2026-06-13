package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private SecretCipher cipher;
    private MailboxManagementService service;

    @BeforeEach
    void setUp() {
        mailboxes = mock(TenantMailboxRepository.class);
        cipher = mock(SecretCipher.class);
        service = new MailboxManagementService(mailboxes, cipher);
    }

    @Test
    void create_encryptsPasswordBeforePersisting() {
        when(cipher.encrypt("s3cret")).thenReturn("ENC(s3cret)");

        service.create("imap.gmail.com", 993, "inbox@acme.co.uk", "s3cret");

        ArgumentCaptor<TenantMailbox> saved = ArgumentCaptor.forClass(TenantMailbox.class);
        verify(mailboxes).save(saved.capture());
        // The row carries the cipher output, not the raw password — encryption happened
        // in the service before persistence (real ciphertext is covered by SecretCipherTest).
        assertThat(saved.getValue().getPasswordEnc()).isEqualTo("ENC(s3cret)");
        assertThat(saved.getValue().isEnabled()).isTrue();
    }

    @Test
    void create_blankFields_rejectedWithoutTouchingCipherOrRepo() {
        assertThatThrownBy(() -> service.create("  ", 993, "user", "pw"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cipher, never()).encrypt(anyString());
        verify(mailboxes, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void create_portOutOfRange_rejected() {
        assertThatThrownBy(() -> service.create("imap.gmail.com", 70000, "user", "pw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Port");
    }
}
