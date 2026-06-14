package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.TrainingExample;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.TrainingExampleRepository;
import com.smartfreight.inbox.service.EmbeddingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.1 S2 — exercises the native pgvector retrieval queries against a real database: cosine
 * ranking, the {@code embedding IS NOT NULL} skip, {@code IS NOT DISTINCT FROM} type matching and
 * the parameterised {@code LIMIT}, plus {@code SELECT *} mapping back onto the entity (whose
 * unmapped {@code embedding} column must be ignored).
 *
 * <p>Same shape as {@link TrainingExampleTenantIsolationTest}: NOT {@code @Transactional} (Hibernate
 * fixes the session tenant at session open), cleanup is a hard DELETE under the system context. All
 * rows are tagged {@code TEST-*} so unrelated data cannot affect assertions.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TrainingExampleRetrievalTest {

    private static final long DEFAULT_TENANT = 1L;

    @Autowired
    private TrainingExampleRepository examples;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> jdbc.update("DELETE FROM training_example WHERE enquiry_text LIKE 'TEST-%'"));
    }

    /** A 768-dim unit vector with a single hot component, as a pgvector literal. */
    private static String unit(int hot) {
        float[] v = new float[768];
        v[hot] = 1.0f;
        return EmbeddingService.toVectorLiteral(v);
    }

    private Long save(String enquiry, TicketType type, String embeddingLiteral) {
        // The @Modifying setEmbedding needs an active transaction (in production it runs inside the
        // send-path TX). TransactionTemplate INSIDE runAs — the tenant is fixed at session open.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return TenantContext.callAs(DEFAULT_TENANT, () -> tx.execute(status -> {
            TrainingExample e = new TrainingExample();
            e.setSourceTicketId(1L);
            e.setTicketType(type);
            e.setEnquiryText(enquiry);
            e.setAiDraft("AI draft");
            e.setOperatorFinal("Operator final for " + enquiry);
            Long id = examples.save(e).getId();
            if (embeddingLiteral != null) {
                examples.setEmbedding(id, embeddingLiteral);
            }
            return id;
        }));
    }

    @Test
    void findSimilar_ranksByCosine_andSkipsNullEmbeddings() {
        save("TEST-A near", TicketType.RFQ, unit(0));
        save("TEST-B far", TicketType.RFQ, unit(5));
        save("TEST-C noembedding", TicketType.RFQ, null);

        List<String> ranked = TenantContext.callAs(DEFAULT_TENANT, () ->
                examples.findSimilar("RFQ", unit(0), 10).stream()
                        .map(TrainingExample::getEnquiryText).toList());

        // Closest to unit(0) first; the embedding-less row is excluded entirely.
        assertThat(ranked).first().isEqualTo("TEST-A near");
        assertThat(ranked).containsExactly("TEST-A near", "TEST-B far");
    }

    @Test
    void findSimilar_filtersByTicketType() {
        save("TEST-RFQ row", TicketType.RFQ, unit(0));
        save("TEST-OTHER row", TicketType.STATUS_UPDATE, unit(0));

        List<String> rfq = TenantContext.callAs(DEFAULT_TENANT, () ->
                examples.findSimilar("RFQ", unit(0), 10).stream()
                        .map(TrainingExample::getEnquiryText).toList());

        assertThat(rfq).containsExactly("TEST-RFQ row");
    }

    @Test
    void findSimilar_respectsLimit() {
        save("TEST-1", TicketType.RFQ, unit(0));
        save("TEST-2", TicketType.RFQ, unit(1));
        save("TEST-3", TicketType.RFQ, unit(2));

        List<TrainingExample> top2 = TenantContext.callAs(DEFAULT_TENANT,
                () -> examples.findSimilar("RFQ", unit(0), 2));

        assertThat(top2).hasSize(2);
    }

    @Test
    void findRecent_returnsAllOfType_includingNullEmbeddings() {
        save("TEST-recent-1", TicketType.RFQ, unit(0));
        save("TEST-recent-2", TicketType.RFQ, null);

        List<String> recent = TenantContext.callAs(DEFAULT_TENANT, () ->
                examples.findRecent("RFQ", 10).stream()
                        .map(TrainingExample::getEnquiryText).toList());

        assertThat(recent).contains("TEST-recent-1", "TEST-recent-2");
    }
}
