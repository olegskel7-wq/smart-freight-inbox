package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.service.MailSenderProvider;
import com.smartfreight.inbox.service.MailboxManagementService;
import com.smartfreight.inbox.service.SecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Multi-inbox reply routing on a real database (V37): a ticket's reply resolves the sender from
 * the mailbox it arrived on — only if that mailbox is enabled, has SMTP and belongs to the
 * CURRENT tenant — and the schema itself refuses a ticket pointing at another tenant's mailbox.
 *
 * <p>The provider is built by hand over the real repository with a real {@link SecretCipher}:
 * the shared test context runs without {@code MAILBOX_ENCRYPTION_KEY} (so no IMAP adapters
 * start), and a key set via properties would fork a second context. Two fresh tenants keep the
 * "oldest SMTP mailbox" fallback deterministic. Same shape as the other isolation tests: NOT
 * {@code @Transactional}, hard cleanup under the system context.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TicketMailboxTenantIsolationTest {

    private static final String TEST_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

    @Autowired
    private TenantMailboxRepository mailboxes;

    @Autowired
    private EmailTicketRepository tickets;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private JdbcTemplate jdbc;

    private final SecretCipher cipher = new SecretCipher(TEST_KEY);
    private final JavaMailSender platformSender = mock(JavaMailSender.class);
    private MailSenderProvider provider;
    private MailboxManagementService mailboxService;

    private Long tenantA;
    private Long tenantB;

    @BeforeEach
    void setUp() {
        provider = new MailSenderProvider(mailboxes, cipher, platformSender, "platform@smartfreight.com");
        mailboxService = new MailboxManagementService(mailboxes, cipher);
        tenantA = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("mbx-a-" + UUID.randomUUID()).build()).getId());
        tenantB = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("mbx-b-" + UUID.randomUUID()).build()).getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM email_ticket WHERE tenant_id IN (?, ?)", tenantA, tenantB);
            jdbc.update("DELETE FROM tenant_mailbox WHERE tenant_id IN (?, ?)", tenantA, tenantB);
            tenants.deleteById(tenantA);
            tenants.deleteById(tenantB);
        });
    }

    private Long mailbox(Long tenant, String address, boolean enabled, boolean smtp) {
        return TenantContext.callAs(tenant, () -> {
            TenantMailbox m = new TenantMailbox();
            m.setHost("imap.test");
            m.setUsername(address);
            m.setPasswordEnc(cipher.encrypt("pw"));
            m.setSmtpHost(smtp ? "smtp.test" : null);
            m.setSmtpPort(smtp ? 587 : null);
            m.setFromAddress(address);
            m.setEnabled(enabled);
            return mailboxes.save(m).getId();
        });
    }

    private Long ticket(Long tenant, Long mailboxId) {
        return TenantContext.callAs(tenant, () -> tickets.save(EmailTicket.builder()
                .messageId("<mbx-" + UUID.randomUUID() + "@test>")
                .senderEmail("customer@acme.test")
                .subject("Quote")
                .mailboxId(mailboxId)
                .build()).getId());
    }

    /** What EmailReplyService does per send: load the ticket, resolve its sender — in the tenant. */
    private String replyFrom(Long tenant, Long ticketId) {
        return TenantContext.callAs(tenant, () ->
                provider.resolve(tickets.findById(ticketId).orElseThrow().getMailboxId()).from());
    }

    private String fromFor(Long tenant, Long mailboxId) {
        return TenantContext.callAs(tenant, () -> provider.resolve(mailboxId).from());
    }

    /** Read straight from the V37 column, not the entity. */
    private Long storedMailboxId(Long ticketId) {
        return TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT mailbox_id FROM email_ticket WHERE id = ?", Long.class, ticketId));
    }

    @Test
    void ticketMailbox_winsOverTheTenantsOlderInbox() {
        mailbox(tenantA, "ops@a.test", true, true);
        Long quotes = mailbox(tenantA, "quotes@a.test", true, true);

        Long ticketId = ticket(tenantA, quotes);

        assertThat(replyFrom(tenantA, ticketId)).isEqualTo("quotes@a.test");
        assertThat(storedMailboxId(ticketId)).isEqualTo(quotes);
    }

    @Test
    void disabledOrSmtpLessTicketMailbox_fallsBackToTheTenantDefault() {
        mailbox(tenantA, "ops@a.test", true, true);
        Long disabled = mailbox(tenantA, "old@a.test", false, true);
        Long imapOnly = mailbox(tenantA, "imap-only@a.test", true, false);

        assertThat(replyFrom(tenantA, ticket(tenantA, disabled))).isEqualTo("ops@a.test");
        assertThat(replyFrom(tenantA, ticket(tenantA, imapOnly))).isEqualTo("ops@a.test");
    }

    @Test
    void anotherTenantsMailboxId_neverResolvesToThatTenantsAccount() {
        Long foreign = mailbox(tenantB, "sales@b.test", true, true);

        // Tenant A has no SMTP of its own → platform, NOT tenant B's account.
        assertThat(fromFor(tenantA, foreign)).isEqualTo("platform@smartfreight.com");

        // With its own inbox, tenant A falls back to that one.
        mailbox(tenantA, "ops@a.test", true, true);
        assertThat(fromFor(tenantA, foreign)).isEqualTo("ops@a.test");

        // Same id seen from its owner resolves normally — the lookup is tenant-scoped, not broken.
        assertThat(fromFor(tenantB, foreign)).isEqualTo("sales@b.test");
    }

    @Test
    void ticketCannotPointAtAnotherTenantsMailbox_compositeForeignKey() {
        Long foreign = mailbox(tenantB, "sales@b.test", true, true);

        assertThatThrownBy(() -> ticket(tenantA, foreign))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheMailbox_keepsTheTicketAndClearsOnlyItsMailbox() {
        mailbox(tenantA, "ops@a.test", true, true);
        Long quotes = mailbox(tenantA, "quotes@a.test", true, true);
        Long ticketId = ticket(tenantA, quotes);

        // As on /settings: delete inside the tenant (RLS on), via the repository.
        TenantContext.runAs(tenantA, () -> mailboxes.deleteById(quotes));

        assertThat(storedMailboxId(ticketId)).isNull();
        assertThat(replyFrom(tenantA, ticketId)).isEqualTo("ops@a.test");
    }

    // ---- Gate B15: edit in place instead of delete + re-add ----

    @Test
    void editingTheMailbox_keepsTheTicketLinkAndUsesTheNewConfig() {
        mailbox(tenantA, "ops@a.test", true, true);
        Long quotes = mailbox(tenantA, "quotes@a.test", true, true);
        Long ticketId = ticket(tenantA, quotes);
        String oldCiphertext = storedPasswordEnc(quotes);

        // Password rotation + new reply address, as the /settings edit form sends it.
        TenantContext.runAs(tenantA, () -> mailboxService.update(quotes, "imap.test", 993, "quotes@a.test",
                "rotated-pw", "smtp.test", 465, "quotes-desk@a.test"));

        assertThat(storedMailboxId(ticketId)).isEqualTo(quotes);
        assertThat(replyFrom(tenantA, ticketId)).isEqualTo("quotes-desk@a.test");
        assertThat(storedPasswordEnc(quotes)).isNotEqualTo(oldCiphertext);
        assertThat(cipher.decrypt(storedPasswordEnc(quotes))).isEqualTo("rotated-pw");
    }

    @Test
    void anotherTenantCannotEditTheMailbox() {
        Long foreign = mailbox(tenantB, "sales@b.test", true, true);

        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> mailboxService.update(foreign,
                "imap.evil", 993, "attacker@a.test", "pw", null, null, null)))
                .isInstanceOf(MailboxManagementService.MailboxNotFoundException.class);

        assertThat(fromFor(tenantB, foreign)).isEqualTo("sales@b.test");
        assertThat(TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT host FROM tenant_mailbox WHERE id = ?", String.class, foreign))).isEqualTo("imap.test");
    }

    @Test
    void editingIntoAnotherMailboxsAccount_isRejectedByTheUniqueConstraint() {
        mailbox(tenantA, "ops@a.test", true, true);
        Long quotes = mailbox(tenantA, "quotes@a.test", true, true);

        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> mailboxService.update(quotes,
                "imap.test", 993, "ops@a.test", null, "smtp.test", 587, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String storedPasswordEnc(Long mailboxId) {
        return TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT password_enc FROM tenant_mailbox WHERE id = ?", String.class, mailboxId));
    }
}
