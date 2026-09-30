package com.smartfreight.inbox.startup;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.service.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.Order;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DemoMailboxBootstrap} — the demo harness upserts encrypted demo
 * mailboxes when on, removes them when off, never touches tenants that don't exist and never
 * aborts startup (Phase 4.8 S2).
 */
class DemoMailboxBootstrapTest {

    private static final String ADDRESS = "ops@felixstowe-freight.example.com";
    private static final String QUOTES_ADDRESS = "quotes@felixstowe-freight.example.com";
    private static final String ROAD_ADDRESS = "dispatch@midlands-haulage.example.com";

    private DemoMailProperties properties;
    private TenantMailboxRepository mailboxes;
    private TenantRepository tenants;
    private SecretCipher cipher;
    private DemoMailboxBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        properties = new DemoMailProperties();
        properties.setInboxes(Map.of(1L, List.of(ADDRESS)));
        mailboxes = mock(TenantMailboxRepository.class);
        tenants = mock(TenantRepository.class);
        cipher = mock(SecretCipher.class);
        bootstrap = new DemoMailboxBootstrap(properties, mailboxes, tenants, cipher);

        when(tenants.existsById(1L)).thenReturn(true);
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.encrypt("demo")).thenReturn("ENC(demo)");
        when(mailboxes.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc()).thenReturn(Optional.empty());
    }

    @Test
    void runsAfterMailboxBootstrap_soADemoRowNeverPreEmptsTheEnvImport() {
        int demo = DemoMailboxBootstrap.class.getAnnotation(Order.class).value();
        int env = MailboxBootstrap.class.getAnnotation(Order.class).value();

        assertThat(demo).isGreaterThan(env);
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
    void enabled_existingRow_rewritesHostAndReEncrypts() {
        properties.setEnabled(true);
        properties.setImapHost("localhost");
        TenantMailbox existing = demoRow();
        existing.setPasswordEnc("ENC(under-rotated-key)");
        when(mailboxes.findFirstByUsernameOrderByIdAsc(ADDRESS)).thenReturn(Optional.of(existing));

        bootstrap.run(null);

        TenantMailbox saved = captureSaved();
        assertThat(saved.getHost()).isEqualTo("localhost");
        // Always re-encrypted under the current key — also heals a row from a rotated key.
        assertThat(saved.getPasswordEnc()).isEqualTo("ENC(demo)");
    }

    @Test
    void enabled_olderSmtpMailboxOnTenant_stillSavesTheDemoRow() {
        properties.setEnabled(true);
        TenantMailbox existing = demoRow();
        when(mailboxes.findFirstByUsernameOrderByIdAsc(ADDRESS)).thenReturn(Optional.of(existing));
        TenantMailbox older = new TenantMailbox();
        older.setId(2L);
        older.setUsername("inbox@real-customer.co.uk");
        when(mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc()).thenReturn(Optional.of(older));

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();

        verify(mailboxes).save(existing);
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
    void oneFailingInbox_neitherAbortsStartupNorBlocksTheNext() {
        properties.setEnabled(true);
        Map<Long, List<String>> inboxes = new LinkedHashMap<>();
        inboxes.put(1L, List.of(ADDRESS));
        inboxes.put(90001L, List.of(ROAD_ADDRESS));
        properties.setInboxes(inboxes);
        when(tenants.existsById(90001L)).thenReturn(true);
        when(mailboxes.findFirstByUsernameOrderByIdAsc(ADDRESS)).thenThrow(new IllegalStateException("db down"));
        when(mailboxes.findFirstByUsernameOrderByIdAsc(ROAD_ADDRESS)).thenReturn(Optional.empty());

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();

        assertThat(captureSaved().getUsername()).isEqualTo(ROAD_ADDRESS);
    }

    @Test
    void twoInboxesOnOneTenant_bothSetUp_andAFailingOneDoesNotBlockItsSibling() {
        properties.setEnabled(true);
        properties.setInboxes(Map.of(1L, List.of(ADDRESS, QUOTES_ADDRESS)));
        when(mailboxes.findFirstByUsernameOrderByIdAsc(ADDRESS)).thenThrow(new IllegalStateException("db down"));
        when(mailboxes.findFirstByUsernameOrderByIdAsc(QUOTES_ADDRESS)).thenReturn(Optional.empty());

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();

        TenantMailbox saved = captureSaved();
        assertThat(saved.getUsername()).isEqualTo(QUOTES_ADDRESS);
        // Each inbox answers FROM its own address.
        assertThat(saved.getFromAddress()).isEqualTo(QUOTES_ADDRESS);
    }

    @Test
    void disabled_removesEveryDemoInboxOfATenant() {
        properties.setInboxes(Map.of(1L, List.of(ADDRESS, QUOTES_ADDRESS)));
        TenantMailbox ops = demoRow();
        TenantMailbox quotes = demoRow();
        quotes.setId(8L);
        quotes.setUsername(QUOTES_ADDRESS);
        when(mailboxes.findByHost("greenmail")).thenReturn(List.of(ops, quotes));

        bootstrap.run(null);

        verify(mailboxes).delete(ops);
        verify(mailboxes).delete(quotes);
    }

    @Test
    void disabled_removesPreviouslyCreatedDemoMailbox() {
        TenantMailbox existing = demoRow();
        when(mailboxes.findByHost("greenmail")).thenReturn(List.of(existing));

        bootstrap.run(null);

        // Removed, not disabled: a lingering row would still count as "tenant has a mailbox"
        // and block MailboxBootstrap's env re-import, and would stay the tenant's SMTP sender.
        verify(mailboxes).delete(existing);
        verify(mailboxes, never()).save(any());
    }

    @Test
    void disabled_demoInboxRenamedOnSettings_stillRemovedByHost() {
        // Gate B15 lets an operator edit a demo inbox's username before it has received mail;
        // matching by the configured address would miss it and leave it pointing at GreenMail.
        TenantMailbox renamed = demoRow();
        renamed.setUsername("renamed@felixstowe-freight.example.com");
        when(mailboxes.findByHost("greenmail")).thenReturn(List.of(renamed));

        bootstrap.run(null);

        verify(mailboxes).delete(renamed);
    }

    @Test
    void disabled_noDemoMailbox_isNoOp() {
        when(mailboxes.findByHost("greenmail")).thenReturn(List.of());

        bootstrap.run(null);

        verify(mailboxes, never()).delete(any());
        verify(mailboxes, never()).save(any());
        verify(cipher, never()).encrypt(anyString());
    }

    @Test
    void disabled_failure_doesNotAbortStartup() {
        when(mailboxes.findByHost("greenmail")).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();
        verify(mailboxes, never()).delete(any());
    }

    private TenantMailbox demoRow() {
        TenantMailbox row = new TenantMailbox();
        row.setId(7L);
        row.setTenantId(1L);
        row.setHost("greenmail");
        row.setUsername(ADDRESS);
        row.setPasswordEnc("ENC(demo)");
        row.setEnabled(true);
        return row;
    }

    private TenantMailbox captureSaved() {
        ArgumentCaptor<TenantMailbox> saved = ArgumentCaptor.forClass(TenantMailbox.class);
        verify(mailboxes).save(saved.capture());
        return saved.getValue();
    }
}
