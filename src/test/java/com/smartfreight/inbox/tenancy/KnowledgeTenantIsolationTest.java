package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.KnowledgeDoc;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.repository.KnowledgeDocRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.9 — proves knowledge_doc isolation on a real database: a document one tenant stores is
 * invisible to every other tenant through both layers (Hibernate {@code @TenantId} for entity reads,
 * the V33 RLS policy for native SQL), while the system context sees across all. The product's whole
 * positioning rests on this — tenant A must never see tenant B's knowledge.
 *
 * <p>Same shape as {@link TrainingExampleTenantIsolationTest}: NOT {@code @Transactional} (Hibernate
 * fixes the session tenant at session open), cleanup is a hard DELETE under the system context.
 * Assertions are scoped by content marker so unrelated rows cannot affect them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KnowledgeTenantIsolationTest {

    private static final long DEFAULT_TENANT = 1L;

    @Autowired
    private KnowledgeDocRepository docs;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private JdbcTemplate jdbc;

    private Long secondTenant;

    @BeforeEach
    void createSecondTenant() {
        secondTenant = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("kb-" + UUID.randomUUID()).build()).getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM knowledge_doc WHERE content LIKE 'TEST-%'");
            tenants.deleteById(secondTenant);
        });
    }

    private void createDoc(Long tenant, String content) {
        TenantContext.runAs(tenant, () -> {
            KnowledgeDoc d = new KnowledgeDoc();
            d.setTitle("KB");
            d.setContent(content);
            d.setSource("manual");
            docs.save(d);
        });
    }

    @Test
    void document_isInvisibleToOtherTenants_viaEntityQuery() {
        createDoc(secondTenant, "TEST-AAAA knowledge");

        List<String> tenant1 = TenantContext.callAs(DEFAULT_TENANT,
                () -> docs.findAllByOrderByCreatedAtDesc().stream().map(KnowledgeDoc::getContent).toList());
        List<String> tenant2 = TenantContext.callAs(secondTenant,
                () -> docs.findAllByOrderByCreatedAtDesc().stream().map(KnowledgeDoc::getContent).toList());

        assertThat(tenant1).doesNotContain("TEST-AAAA knowledge");
        assertThat(tenant2).contains("TEST-AAAA knowledge");
    }

    @Test
    void nativeSql_isGuardedByRls() {
        createDoc(secondTenant, "TEST-BBBB knowledge");

        Long visibleToTenant1 = TenantContext.callAs(DEFAULT_TENANT, () -> jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_doc WHERE content = ?", Long.class, "TEST-BBBB knowledge"));
        Long visibleToSystem = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_doc WHERE content = ?", Long.class, "TEST-BBBB knowledge"));

        assertThat(visibleToTenant1).isZero();
        assertThat(visibleToSystem).isEqualTo(1L);
    }

    @Test
    void systemContext_seesAllTenants() {
        createDoc(secondTenant, "TEST-CCCC knowledge");
        createDoc(DEFAULT_TENANT, "TEST-DDDD knowledge");

        List<String> systemView = TenantContext.callAsSystem(
                () -> docs.findAllByOrderByCreatedAtDesc().stream().map(KnowledgeDoc::getContent).toList());

        assertThat(systemView).contains("TEST-CCCC knowledge", "TEST-DDDD knowledge");
    }
}
