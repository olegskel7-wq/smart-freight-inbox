package com.smartfreight.inbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * One stored knowledge-base document (Phase 3.9 — Knowledge-base deflection).
 *
 * <p>An operator authors free-text knowledge ({@link #title} + {@link #content} — a FAQ answer, a
 * policy, a quoting rule). At draft time the pipeline retrieves the most semantically similar
 * documents for the current tenant and grounds the reply with them, so typical "how / where / when"
 * enquiries get a substantive answer even when there is no structured backend data.
 *
 * <p>Guarded by both isolation layers: {@code @TenantId} for entity queries and the V33 RLS policy
 * for native SQL / raw JDBC — identical to {@link TrainingExample}/{@link TenantDocument}.
 *
 * <p>The {@code embedding} column ({@code vector(768)}, nomic-embed-text) is deliberately NOT mapped
 * here: pgvector is a vendor type, so it is written/read via native SQL in
 * {@link com.smartfreight.inbox.repository.KnowledgeDocRepository} to keep the entity portable.
 */
@Entity
@Table(name = "knowledge_doc")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant — assigned on insert, filters every entity query. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** Short label for the document (shown in the management table). */
    @Column(name = "title", nullable = false)
    private String title;

    /** The knowledge text itself — embedded (with the title) and injected into the drafting prompt. */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /** Provenance: {@code "manual"} for operator text entry, or the uploaded file name (S2). */
    @Column(name = "source")
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
