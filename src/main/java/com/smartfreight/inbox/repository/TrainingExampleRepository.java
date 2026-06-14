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
}
