package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.TrainingExample;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.repository.TrainingExampleRepository;
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
 * Phase 3.1 — proves training_example isolation on a real database: a correction one tenant
 * captures is invisible to every other tenant through both layers (Hibernate {@code @TenantId}
 * for entity reads, the V28 RLS policy for native SQL), while the system context sees across all.
 *
 * <p>Same shape as {@link ShipmentWatchTenantIsolationTest}: NOT {@code @Transactional} (Hibernate
 * fixes the session tenant at session open), cleanup is a hard DELETE under the system context.
 * Assertions are scoped by enquiry marker so unrelated rows cannot affect them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TrainingExampleTenantIsolationTest {

    private static final long DEFAULT_TENANT = 1L;

    @Autowired
    private TrainingExampleRepository examples;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private JdbcTemplate jdbc;

    private Long secondTenant;

    @BeforeEach
    void createSecondTenant() {
        secondTenant = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("train-" + UUID.randomUUID()).build()).getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM training_example WHERE enquiry_text LIKE 'TEST-%'");
            tenants.deleteById(secondTenant);
        });
    }

    private void createExample(Long tenant, String enquiry) {
        TenantContext.runAs(tenant, () -> {
            TrainingExample e = new TrainingExample();
            e.setSourceTicketId(1L);
            e.setTicketType(TicketType.RFQ);
            e.setEnquiryText(enquiry);
            e.setAiDraft("AI draft");
            e.setOperatorFinal("Operator final");
            examples.save(e);
        });
    }

    @Test
    void example_isInvisibleToOtherTenants_viaEntityQuery() {
        createExample(secondTenant, "TEST-AAAA enquiry");

        List<String> tenant1 = TenantContext.callAs(DEFAULT_TENANT,
                () -> examples.findAllByOrderByCreatedAtDesc().stream().map(TrainingExample::getEnquiryText).toList());
        List<String> tenant2 = TenantContext.callAs(secondTenant,
                () -> examples.findAllByOrderByCreatedAtDesc().stream().map(TrainingExample::getEnquiryText).toList());

        assertThat(tenant1).doesNotContain("TEST-AAAA enquiry");
        assertThat(tenant2).contains("TEST-AAAA enquiry");
    }

    @Test
    void nativeSql_isGuardedByRls() {
        createExample(secondTenant, "TEST-BBBB enquiry");

        Long visibleToTenant1 = TenantContext.callAs(DEFAULT_TENANT, () -> jdbc.queryForObject(
                "SELECT count(*) FROM training_example WHERE enquiry_text = ?", Long.class, "TEST-BBBB enquiry"));
        Long visibleToSystem = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT count(*) FROM training_example WHERE enquiry_text = ?", Long.class, "TEST-BBBB enquiry"));

        assertThat(visibleToTenant1).isZero();
        assertThat(visibleToSystem).isEqualTo(1L);
    }

    @Test
    void systemContext_seesAllTenants() {
        createExample(secondTenant, "TEST-CCCC enquiry");
        createExample(DEFAULT_TENANT, "TEST-DDDD enquiry");

        List<String> systemView = TenantContext.callAsSystem(
                () -> examples.findAllByOrderByCreatedAtDesc().stream().map(TrainingExample::getEnquiryText).toList());

        assertThat(systemView).contains("TEST-CCCC enquiry", "TEST-DDDD enquiry");
    }
}
