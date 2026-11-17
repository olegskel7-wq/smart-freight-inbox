package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.MailboxAuthType;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.entity.TenantMs365Directory;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.repository.TenantMs365DirectoryRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V38 on a real database: an MS365_OAUTH mailbox is stored without a password, pre-V38-shaped
 * rows default to PASSWORD, and the CHECK makes a half-configured row of either type unrepresentable.
 * The directory binding: one Microsoft directory belongs to one tenant, and a mailbox can only name
 * a directory bound to its own tenant — tenant A cannot point a mailbox at customer B's directory.
 * NOT {@code @Transactional} — hard cleanup under the system context, like the isolation tests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class Ms365MailboxSchemaTest {

    @Autowired
    private TenantMailboxRepository mailboxes;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private TenantMs365DirectoryRepository directories;

    @Autowired
    private JdbcTemplate jdbc;

    private Long tenantId;
    private Long otherTenantId;
    private final String directory = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        tenantId = newTenant();
        otherTenantId = newTenant();
    }

    private Long newTenant() {
        return TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("ms365-" + UUID.randomUUID()).build()).getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            for (Long id : new Long[]{tenantId, otherTenantId}) {
                jdbc.update("DELETE FROM tenant_mailbox WHERE tenant_id = ?", id);
                jdbc.update("DELETE FROM tenant_ms365_directory WHERE tenant_id = ?", id);
                tenants.deleteById(id);
            }
        });
    }

    private void bind(Long tenant, String directoryId) {
        TenantContext.runAs(tenant, () -> {
            TenantMs365Directory d = new TenantMs365Directory();
            d.setDirectoryId(directoryId);
            directories.saveAndFlush(d);
        });
    }

    private TenantMailbox oauthMailbox(String username, String directoryId) {
        TenantMailbox m = mailbox(username);
        m.setAuthType(MailboxAuthType.MS365_OAUTH);
        m.setMsDirectoryId(directoryId);
        return m;
    }

    private TenantMailbox mailbox(String username) {
        TenantMailbox m = new TenantMailbox();
        m.setHost("outlook.office365.com");
        m.setUsername(username);
        return m;
    }

    @Test
    void oauthMailbox_roundTripsWithoutAPassword() {
        bind(tenantId, directory);
        Long id = TenantContext.callAs(tenantId, () -> mailboxes.save(oauthMailbox("ops@contoso.test", directory)).getId());

        TenantMailbox stored = TenantContext.callAs(tenantId, () -> mailboxes.findById(id).orElseThrow());
        assertThat(stored.getAuthType()).isEqualTo(MailboxAuthType.MS365_OAUTH);
        assertThat(stored.getMsDirectoryId()).isEqualTo(directory);
        assertThat(stored.getPasswordEnc()).isNull();
    }

    @Test
    void rowWithoutAuthType_defaultsToPassword() {
        TenantContext.runAsSystem(() -> jdbc.update(
                "INSERT INTO tenant_mailbox (tenant_id, host, username, password_enc) VALUES (?, 'imap.test', 'old@a.test', 'enc')",
                tenantId));

        String authType = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT auth_type FROM tenant_mailbox WHERE tenant_id = ?", String.class, tenantId));
        assertThat(authType).isEqualTo("PASSWORD");
    }

    @Test
    void checkRefusesAPasswordMailboxWithoutPassword() {
        assertThatThrownBy(() -> TenantContext.runAs(tenantId, () -> mailboxes.saveAndFlush(mailbox("pw@a.test"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkRefusesAnOauthMailboxWithoutDirectory() {
        assertThatThrownBy(() -> TenantContext.runAs(tenantId, () -> {
            TenantMailbox m = mailbox("oauth@a.test");
            m.setAuthType(MailboxAuthType.MS365_OAUTH);
            mailboxes.saveAndFlush(m);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oauthMailbox_withADirectoryNotBoundToItsTenant_isRefused() {
        // Tenant A types in customer B's directory id: the composite FK has no (A, directory) row.
        bind(otherTenantId, directory);

        assertThatThrownBy(() -> TenantContext.runAs(tenantId,
                () -> mailboxes.saveAndFlush(oauthMailbox("ops@b.test", directory))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDirectoryBelongsToOneTenantOnly() {
        bind(otherTenantId, directory);

        assertThatThrownBy(() -> bind(tenantId, directory)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anotherTenantsBinding_isInvisible() {
        bind(otherTenantId, directory);

        assertThat(TenantContext.callAs(tenantId, () -> directories.findByDirectoryId(directory))).isEmpty();
        assertThat(TenantContext.callAs(otherTenantId, () -> directories.findByDirectoryId(directory))).isPresent();
    }
}
