package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Access to the per-tenant document store (Phase 2.7).
 *
 * <p>Tenant-filtered by Hibernate {@code @TenantId}; every call site (web requests, the pipeline
 * consumer, both send paths) runs inside a tenant scope, so results never leak across tenants.
 */
public interface TenantDocumentRepository extends JpaRepository<TenantDocument, Long> {

    /**
     * The most recently uploaded document of a given type for a shipment — what the pipeline
     * attaches when a DOCUMENT_REQUEST is matched. Latest wins if several were uploaded.
     */
    Optional<TenantDocument> findFirstByIdentifierAndDocTypeOrderByUploadedAtDesc(
            String identifier, DocumentType docType);

    /** All documents held for a shipment — backs the operator's manual-attach dropdown. */
    List<TenantDocument> findByIdentifierOrderByUploadedAtDesc(String identifier);

    /** Full store listing for the /documents management page (newest first). */
    List<TenantDocument> findAllByOrderByUploadedAtDesc();
}
