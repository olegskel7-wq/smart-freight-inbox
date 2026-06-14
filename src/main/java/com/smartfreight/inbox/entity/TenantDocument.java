package com.smartfreight.inbox.entity;

import com.smartfreight.inbox.entity.enums.DocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * A freight document held on behalf of a tenant (Phase 2.7).
 *
 * <p>Operators upload documents (a POD, bill of lading, customs paper, …) keyed by the shipment
 * {@code identifier} they relate to. When a DOCUMENT_REQUEST enquiry arrives, the pipeline
 * detects which type is being asked for and looks up the matching document here; if found, it is
 * attached to the reply, otherwise the request is escalated to the operator.
 *
 * <p>Guarded by both isolation layers: {@code @TenantId} for entity queries and the V26 RLS
 * policy for native SQL / raw JDBC — identical to {@link ShipmentWatch}/{@link QuoteFollowUp}.
 * The store is inbox-side (operator-owned content), not port-service data.
 */
@Entity
@Table(name = "tenant_document")
@Getter
@Setter
@NoArgsConstructor
public class TenantDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant — assigned on insert, filters every entity query. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** Shipment reference this document belongs to (e.g. an ISO 6346 container number). */
    @Column(name = "identifier", nullable = false)
    private String identifier;

    /** Which kind of document this is — matched against the AI-detected request type. */
    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 50)
    private DocumentType docType;

    /** Original upload filename, reused as the attachment filename on send. */
    @Column(name = "filename", nullable = false, length = 512)
    private String filename;

    /** MIME type recorded at upload (e.g. application/pdf); used as the attachment content type. */
    @Column(name = "content_type", length = 255)
    private String contentType;

    /**
     * File bytes, stored inline as Postgres BYTEA. {@code @JdbcTypeCode(VARBINARY)} forces the
     * BYTEA mapping — a bare {@code @Lob byte[]} would map to a Postgres large object (oid),
     * which is not what the V26 column is.
     */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    /** Operator who uploaded the document (principal name), for audit. */
    @Column(name = "uploaded_by", length = 255)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();
}
