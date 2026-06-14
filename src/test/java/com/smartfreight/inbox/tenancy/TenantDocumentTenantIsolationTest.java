package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2.7 — proves tenant_document isolation on a real database: a document one tenant stores is
 * invisible to every other tenant through both layers (Hibernate {@code @TenantId} for entity reads,
 * the V26 RLS policy for native SQL).
 *
 * <p>Same shape as {@link ShipmentWatchTenantIsolationTest}: NOT {@code @Transactional} (Hibernate
 * fixes the session tenant at session open), cleanup is a hard DELETE under the system context,
 * assertions scoped by identifier so unrelated cached-context rows cannot affect them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TenantDocumentTenantIsolationTest {

    private static final long DEFAULT_TENANT = 1L;

    @Autowired
    private TenantDocumentRepository documents;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private JdbcTemplate jdbc;

    private Long secondTenant;

    @BeforeEach
    void createSecondTenant() {
        secondTenant = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("doc-" + UUID.randomUUID()).build()).getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM tenant_document WHERE identifier LIKE 'TEST-%'");
            tenants.deleteById(secondTenant);
        });
    }

    private void storeDocument(Long tenant, String identifier) {
        TenantContext.runAs(tenant, () -> {
            TenantDocument d = new TenantDocument();
            d.setIdentifier(identifier);
            d.setDocType(DocumentType.POD);
            d.setFilename("pod.pdf");
            d.setContentType("application/pdf");
            d.setContent(new byte[]{1, 2, 3, 4});
            d.setFileSize(4);
            documents.save(d);
        });
    }

    @Test
    void document_isInvisibleToOtherTenants_viaEntityQuery() {
        storeDocument(secondTenant, "TEST-AAAA");

        var tenant1 = TenantContext.callAs(DEFAULT_TENANT,
                () -> documents.findByIdentifierOrderByUploadedAtDesc("TEST-AAAA"));
        var tenant2 = TenantContext.callAs(secondTenant,
                () -> documents.findByIdentifierOrderByUploadedAtDesc("TEST-AAAA"));

        assertThat(tenant1).isEmpty();
        assertThat(tenant2).hasSize(1);
    }

    @Test
    void nativeSql_isGuardedByRls() {
        storeDocument(secondTenant, "TEST-BBBB");

        // Raw SQL bypasses @TenantId — only the V26 policy stands between tenants.
        Long visibleToTenant1 = TenantContext.callAs(DEFAULT_TENANT, () -> jdbc.queryForObject(
                "SELECT count(*) FROM tenant_document WHERE identifier = ?", Long.class, "TEST-BBBB"));
        Long visibleToSystem = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT count(*) FROM tenant_document WHERE identifier = ?", Long.class, "TEST-BBBB"));

        assertThat(visibleToTenant1).isZero();
        assertThat(visibleToSystem).isEqualTo(1L);
    }

    @Test
    void findFirstByType_isTenantScoped() {
        storeDocument(secondTenant, "TEST-CCCC");

        var tenant1 = TenantContext.callAs(DEFAULT_TENANT,
                () -> documents.findFirstByIdentifierAndDocTypeOrderByUploadedAtDesc("TEST-CCCC", DocumentType.POD));
        var tenant2 = TenantContext.callAs(secondTenant,
                () -> documents.findFirstByIdentifierAndDocTypeOrderByUploadedAtDesc("TEST-CCCC", DocumentType.POD));

        assertThat(tenant1).isEmpty();
        assertThat(tenant2).isPresent();
    }
}
