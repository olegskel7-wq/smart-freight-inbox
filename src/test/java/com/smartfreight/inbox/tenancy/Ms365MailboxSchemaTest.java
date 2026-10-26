package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.MailboxAuthType;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
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
    private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("ms365-" + UUID.randomUUID()).build()).getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM tenant_mailbox WHERE tenant_id = ?", tenantId);
            tenants.deleteById(tenantId);
        });
    }

    private TenantMailbox mailbox(String username) {
        TenantMailbox m = new TenantMailbox();
        m.setHost("outlook.office365.com");
        m.setUsername(username);
        return m;
    }

    @Test
    void oauthMailbox_roundTripsWithoutAPassword() {
        Long id = TenantContext.callAs(tenantId, () -> {
            TenantMailbox m = mailbox("ops@contoso.test");
            m.setAuthType(MailboxAuthType.MS365_OAUTH);
            m.setMsDirectoryId("contoso.onmicrosoft.com");
            return mailboxes.save(m).getId();
        });

        TenantMailbox stored = TenantContext.callAs(tenantId, () -> mailboxes.findById(id).orElseThrow());
        assertThat(stored.getAuthType()).isEqualTo(MailboxAuthType.MS365_OAUTH);
        assertThat(stored.getMsDirectoryId()).isEqualTo("contoso.onmicrosoft.com");
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
}
