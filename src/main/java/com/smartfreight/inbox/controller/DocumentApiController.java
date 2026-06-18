package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.DocumentRefDto;
import com.smartfreight.inbox.controller.dto.DocumentsResponse;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.DocumentService;
import com.smartfreight.inbox.service.SettingsService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

/**
 * JSON API backing the React {@code /documents} page (Phase 3.2). It mirrors {@link DocumentController}
 * (the Thymeleaf document-store UI) but speaks DTOs over {@code /api/documents} and is secured by the
 * stateless resource-server chain (bearer JWT). The two controllers run side by side during the
 * migration; this one collapses to the only one when Thymeleaf is removed.
 *
 * <p>Storage is delegated to {@link DocumentService} (same thin-controller split as the Thymeleaf page).
 * Upload and delete return the refreshed snapshot so the SPA re-renders in one round trip; download
 * streams the file bytes with a {@code Content-Disposition} attachment header (the SPA fetches it with
 * the bearer token and saves it as a blob — a plain link can't carry the token).
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentApiController {

    private final DocumentService documentService;
    private final TenantDocumentRepository documentRepository;
    private final SettingsService settingsService;

    public DocumentApiController(DocumentService documentService,
                                 TenantDocumentRepository documentRepository,
                                 SettingsService settingsService) {
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.settingsService = settingsService;
    }

    /** GET /api/documents — the current tenant's stored documents, the type options and the flag state. */
    @GetMapping
    public DocumentsResponse get() {
        return buildResponse();
    }

    /**
     * POST /api/documents/upload — stores an uploaded file (multipart) against a shipment identifier and
     * type. A missing/empty file or a blank identifier answers {@code 400}; the refreshed snapshot is
     * returned on success.
     */
    @PostMapping("/upload")
    public DocumentsResponse upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("identifier") String identifier,
                                    @RequestParam("docType") DocumentType docType) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please choose a file to upload.");
        }
        if (identifier == null || identifier.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shipment identifier is required.");
        }
        try {
            documentService.store(identifier, docType, file);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload failed: " + e.getMessage());
        }
        return buildResponse();
    }

    /**
     * GET /api/documents/{id}/download — streams the file bytes as an attachment. RLS scopes the lookup
     * to the current tenant; an unknown id answers {@code 404}.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<ByteArrayResource> download(@PathVariable Long id) {
        return documentRepository.findById(id)
                .map(doc -> ResponseEntity.ok()
                        .contentType(doc.getContentType() != null
                                ? MediaType.parseMediaType(doc.getContentType())
                                : MediaType.APPLICATION_OCTET_STREAM)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + doc.getFilename() + "\"")
                        .body(new ByteArrayResource(doc.getContent())))
                .orElse(ResponseEntity.notFound().build());
    }

    /** DELETE /api/documents/{id} — removes one document. RLS scopes the delete to the current tenant. */
    @DeleteMapping("/{id}")
    public DocumentsResponse delete(@PathVariable Long id) {
        documentRepository.deleteById(id);
        return buildResponse();
    }

    /** Snapshots the current tenant's documents (newest first) plus the selectable type options. */
    private DocumentsResponse buildResponse() {
        List<DocumentRefDto> documents = documentRepository.findAllByOrderByUploadedAtDesc().stream()
                .map(DocumentRefDto::from)
                .toList();
        List<DocumentsResponse.DocTypeOption> docTypes = Arrays.stream(DocumentType.values())
                .map(t -> new DocumentsResponse.DocTypeOption(t.name(), t.getLabel()))
                .toList();
        boolean featureEnabled = settingsService.isFeatureEnabled(DocumentService.FEATURE_KEY, true);
        return new DocumentsResponse(featureEnabled, documents, docTypes);
    }
}
