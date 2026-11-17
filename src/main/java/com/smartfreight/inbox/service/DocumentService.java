package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Stores, finds and removes per-tenant freight documents.
 *
 * <p>Keeps {@code DocumentApiController} thin — the same split as {@link RateSheetService} behind the
 * rate-sheet controller. Persistence is tenant-scoped by Hibernate {@code @TenantId} + RLS: the row is
 * stamped with the current tenant on insert, and another tenant's document is never found.
 */
@Service
public class DocumentService {

    /** Feature flag — see V26. ON by default: a document reply is always operator-reviewed. */
    public static final String FEATURE_KEY = "feature.document-request";

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final TenantDocumentRepository documentRepository;

    public DocumentService(TenantDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * Stores an uploaded file as a tenant document keyed by shipment identifier and type.
     *
     * @param identifier shipment reference the document relates to (trimmed)
     * @param docType    document type tag
     * @param file       the uploaded file
     * @return the saved document
     * @throws IOException if the file bytes cannot be read
     */
    public TenantDocument store(String identifier, DocumentType docType, MultipartFile file) throws IOException {
        TenantDocument doc = new TenantDocument();
        doc.setIdentifier(identifier.trim());
        doc.setDocType(docType);
        doc.setFilename(file.getOriginalFilename() != null ? file.getOriginalFilename() : "document");
        doc.setContentType(file.getContentType());
        doc.setContent(file.getBytes());
        doc.setFileSize(file.getSize());
        doc.setUploadedBy(currentUser());
        TenantDocument saved = documentRepository.save(doc);
        log.info("Stored document {} ({}) for identifier {} ({} bytes)",
                saved.getId(), docType, saved.getIdentifier(), saved.getFileSize());
        return saved;
    }

    public Optional<TenantDocument> find(Long id) {
        return documentRepository.findById(id);
    }

    /** The current tenant's documents, newest first. */
    public List<TenantDocument> listNewestFirst() {
        return documentRepository.findAllByOrderByUploadedAtDesc();
    }

    /** The documents filed under one shipment identifier, newest first. */
    public List<TenantDocument> forIdentifier(String identifier) {
        return documentRepository.findByIdentifierOrderByUploadedAtDesc(identifier);
    }

    /**
     * Deletes the document if the current tenant has it. Idempotent: an unknown or cross-tenant id
     * (invisible under RLS) is a no-op instead of the error a bare {@code deleteById} raises.
     */
    public void deleteIfPresent(Long id) {
        if (documentRepository.existsById(id)) {
            documentRepository.deleteById(id);
        }
    }

    /** Current authenticated operator name, or {@code "unknown"} when there is no principal. */
    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getName() != null) ? auth.getName() : "unknown";
    }
}
