package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.TrainingExample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Access to the captured-corrections store (Phase 3.1).
 *
 * <p>Tenant-filtered by Hibernate {@code @TenantId}; every call site (the send path, the
 * /training-examples page, the pipeline in Session 2) runs inside a tenant scope, so results
 * never leak across tenants.
 *
 * <p>The {@code embedding} column is a pgvector type the entity does not map, so it is written
 * via native SQL ({@link #setEmbedding}) with an explicit {@code ::vector} cast. Retrieval
 * (cosine search) lands in Session 2.
 */
public interface TrainingExampleRepository extends JpaRepository<TrainingExample, Long> {

    /** Full listing for the /training-examples management page (newest first). */
    List<TrainingExample> findAllByOrderByCreatedAtDesc();

    /**
     * Stores the embedding for a just-saved example. The vector is passed as its pgvector text
     * literal (e.g. {@code "[0.1,0.2,...]"}) and cast to {@code vector} in SQL — keeping the JDBC
     * binding a plain string avoids a vendor type on the classpath. RLS still applies (the call
     * runs in the owning tenant's context), so a wrong-tenant id updates nothing.
     *
     * @param id     training_example id
     * @param vector pgvector text literal of the embedding
     */
    @Modifying
    @Query(value = "UPDATE training_example SET embedding = CAST(:vector AS vector) WHERE id = :id",
            nativeQuery = true)
    void setEmbedding(@Param("id") Long id, @Param("vector") String vector);

    /**
     * The {@code limit} most semantically similar past corrections for a ticket type (Phase 3.1 S2).
     *
     * <p>Ranks by pgvector cosine distance ({@code <=>}) against the embedded incoming enquiry,
     * pre-filtered to the same ticket type so a document-request correction never informs an RFQ
     * draft. {@code IS NOT DISTINCT FROM} matches a null type to null (a GENERAL/unclassified
     * enquiry). Examples without an embedding (model was down at capture, backfillable) are skipped
     * — they cannot be ranked. RLS scopes the search to the current tenant; the entity does not map
     * the {@code embedding} column, so the extra select column is simply ignored on mapping.
     *
     * @param ticketType TicketType enum name, or null for unclassified
     * @param vector     pgvector text literal of the query enquiry's embedding
     * @param limit      maximum examples to return
     */
    @Query(value = """
            SELECT * FROM training_example
            WHERE ticket_type IS NOT DISTINCT FROM :ticketType
              AND embedding IS NOT NULL
            ORDER BY embedding <=> CAST(:vector AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<TrainingExample> findSimilar(@Param("ticketType") String ticketType,
                                      @Param("vector") String vector,
                                      @Param("limit") int limit);

    /**
     * Recency fallback for retrieval (Phase 3.1 S2): the newest {@code limit} corrections for a
     * ticket type, used when the enquiry could not be embedded (Ollama down / breaker open) so the
     * feedback loop still contributes examples — mirrors how {@code InboxQueryService} degrades.
     *
     * @param ticketType TicketType enum name, or null for unclassified
     * @param limit      maximum examples to return
     */
    @Query(value = """
            SELECT * FROM training_example
            WHERE ticket_type IS NOT DISTINCT FROM :ticketType
            ORDER BY created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<TrainingExample> findRecent(@Param("ticketType") String ticketType,
                                     @Param("limit") int limit);
}
