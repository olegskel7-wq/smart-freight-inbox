package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.KnowledgeDoc;
import com.smartfreight.inbox.repository.KnowledgeDocRepository;
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
 * Phase 3.9 — exercises the native pgvector retrieval query for the knowledge base against a real
 * database: cosine ranking, the {@code embedding IS NOT NULL} skip, the relevance-threshold filter
 * ({@code < :maxDistance}) and the parameterised {@code LIMIT}, plus {@code SELECT *} mapping back
 * onto the entity (whose unmapped {@code embedding} column must be ignored).
 *
 * <p>Same shape as {@link TrainingExampleRetrievalTest}: NOT {@code @Transactional} (Hibernate fixes
 * the session tenant at session open), cleanup is a hard DELETE under the system context. All rows
 * are tagged {@code TEST-*} so unrelated data cannot affect assertions.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KnowledgeRetrievalTest {

    private static final long DEFAULT_TENANT = 1L;
    /** Wide enough that the relevance gate does not filter (orthogonal cosine distance is 1.0). */
    private static final double NO_THRESHOLD = 2.0;

    @Autowired
    private KnowledgeDocRepository docs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> jdbc.update("DELETE FROM knowledge_doc WHERE content LIKE 'TEST-%'"));
    }

    /** A 768-dim unit vector with a single hot component, as a pgvector literal. */
    private static String unit(int hot) {
        float[] v = new float[768];
        v[hot] = 1.0f;
        return EmbeddingService.toVectorLiteral(v);
    }

    private Long save(String content, String embeddingLiteral) {
        // The @Modifying setEmbedding needs an active transaction (in production it runs inside the
        // create/update TX). TransactionTemplate INSIDE callAs — the tenant is fixed at session open.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return TenantContext.callAs(DEFAULT_TENANT, () -> tx.execute(status -> {
            KnowledgeDoc d = new KnowledgeDoc();
            d.setTitle("KB");
            d.setContent(content);
            d.setSource("manual");
            Long id = docs.save(d).getId();
            if (embeddingLiteral != null) {
                docs.setEmbedding(id, embeddingLiteral);
            }
            return id;
        }));
    }

    @Test
    void findSimilar_ranksByCosine_andSkipsNullEmbeddings() {
        save("TEST-A near", unit(0));
        save("TEST-B far", unit(5));
        save("TEST-C noembedding", null);

        List<String> ranked = TenantContext.callAs(DEFAULT_TENANT, () ->
                docs.findSimilar(unit(0), NO_THRESHOLD, 10).stream()
                        .map(KnowledgeDoc::getContent).toList());

        // Closest to unit(0) first; the embedding-less row is excluded entirely.
        assertThat(ranked).first().isEqualTo("TEST-A near");
        assertThat(ranked).containsExactly("TEST-A near", "TEST-B far");
    }

    @Test
    void findSimilar_filtersByDistanceThreshold() {
        save("TEST-near", unit(0));   // distance 0.0 to the query
        save("TEST-far", unit(5));    // orthogonal → distance 1.0

        List<String> close = TenantContext.callAs(DEFAULT_TENANT, () ->
                docs.findSimilar(unit(0), 0.5, 10).stream()
                        .map(KnowledgeDoc::getContent).toList());

        // Only the document inside the 0.5 threshold survives; the orthogonal one is filtered out.
        assertThat(close).containsExactly("TEST-near");
    }

    @Test
    void findSimilar_respectsLimit() {
        save("TEST-1", unit(0));
        save("TEST-2", unit(1));
        save("TEST-3", unit(2));

        List<KnowledgeDoc> top2 = TenantContext.callAs(DEFAULT_TENANT,
                () -> docs.findSimilar(unit(0), NO_THRESHOLD, 2));

        assertThat(top2).hasSize(2);
    }
}
