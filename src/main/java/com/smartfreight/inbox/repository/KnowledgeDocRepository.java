package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.KnowledgeDoc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Access to the per-tenant knowledge store (Phase 3.9).
 *
 * <p>Tenant-filtered by Hibernate {@code @TenantId}; every call site (the management page, the
 * pipeline) runs inside a tenant scope, so results never leak across tenants.
 *
 * <p>The {@code embedding} column is a pgvector type the entity does not map, so it is written via
 * native SQL ({@link #setEmbedding}) with an explicit {@code ::vector} cast and read back by the
 * cosine search ({@link #findSimilar}). Mirrors {@link TrainingExampleRepository}.
 */
public interface KnowledgeDocRepository extends JpaRepository<KnowledgeDoc, Long> {

    /** Full listing for the /knowledge management page (newest first). */
    List<KnowledgeDoc> findAllByOrderByCreatedAtDesc();

    /**
     * Stores the embedding for a just-saved document. The vector is passed as its pgvector text
     * literal (e.g. {@code "[0.1,0.2,...]"}) and cast to {@code vector} in SQL — keeping the JDBC
     * binding a plain string avoids a vendor type on the classpath. RLS still applies (the call
     * runs in the owning tenant's context), so a wrong-tenant id updates nothing.
     *
     * <p>{@code @Transactional} so it runs in its own short transaction: the service writes the
     * embedding OUTSIDE the create/update transaction (the Ollama call must not hold a DB connection),
     * and a {@code @Modifying} native query needs an active transaction. When called from a path that
     * already has one (the feedback loop's send transaction) it simply joins (REQUIRED).
     *
     * @param id     knowledge_doc id
     * @param vector pgvector text literal of the embedding
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE knowledge_doc SET embedding = CAST(:vector AS vector) WHERE id = :id",
            nativeQuery = true)
    void setEmbedding(@Param("id") Long id, @Param("vector") String vector);

    /**
     * The {@code limit} most semantically similar knowledge documents to the embedded enquiry,
     * within a relevance threshold (Phase 3.9).
     *
     * <p>Ranks by pgvector cosine distance ({@code <=>}) against the embedded incoming enquiry. Unlike
     * the feedback loop (V28), knowledge is NOT pre-filtered by ticket type — a policy or FAQ can be
     * relevant to any enquiry; RLS is the only scope. A relevance gate ({@code < :maxDistance}) keeps an
     * irrelevant document from grounding the draft (an off-topic FAQ pollutes a reply more than a missing
     * few-shot example does, so the threshold matters more here than for V28). Documents without an
     * embedding (model down at capture, backfillable) are skipped — they cannot be ranked.
     *
     * @param vector      pgvector text literal of the query enquiry's embedding
     * @param maxDistance cosine-distance ceiling (0 = identical, 2 = opposite); only closer docs match
     * @param limit       maximum documents to return
     */
    @Query(value = """
            SELECT * FROM knowledge_doc
            WHERE embedding IS NOT NULL
              AND embedding <=> CAST(:vector AS vector) < :maxDistance
            ORDER BY embedding <=> CAST(:vector AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<KnowledgeDoc> findSimilar(@Param("vector") String vector,
                                   @Param("maxDistance") double maxDistance,
                                   @Param("limit") int limit);
}
