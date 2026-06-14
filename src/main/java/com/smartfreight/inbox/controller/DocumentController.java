package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.DocumentService;
import com.smartfreight.inbox.service.SettingsService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Per-tenant document store management (Phase 2.7): table view, file upload, download, delete.
 *
 * <p>Mirrors {@link RateSheetController}: thin controller delegating the save to
 * {@link DocumentService}, flash messages for feedback. Documents uploaded here are matched to
 * DOCUMENT_REQUEST enquiries by shipment identifier + type and attached to the reply.
 */
@Controller
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final TenantDocumentRepository documentRepository;
    private final SettingsService settingsService;

    public DocumentController(DocumentService documentService,
                              TenantDocumentRepository documentRepository,
                              SettingsService settingsService) {
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.settingsService = settingsService;
    }

    @GetMapping
    public String documentsPage(Model model) {
        model.addAttribute("documents", documentRepository.findAllByOrderByUploadedAtDesc());
        model.addAttribute("docTypes", DocumentType.values());
        model.addAttribute("featureEnabled",
                settingsService.isFeatureEnabled(DocumentService.FEATURE_KEY, true));
        return "documents";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam("identifier") String identifier,
                         @RequestParam("docType") DocumentType docType,
                         RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please choose a file to upload.");
            return "redirect:/documents";
        }
        if (identifier == null || identifier.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Shipment identifier is required.");
            return "redirect:/documents";
        }
        try {
            documentService.store(identifier, docType, file);
            redirectAttributes.addFlashAttribute("successMessage", String.format(
                    "Stored %s for %s.", docType.getLabel(), identifier.trim()));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Upload failed: " + e.getMessage());
        }
        return "redirect:/documents";
    }

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

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        documentRepository.deleteById(id);
        redirectAttributes.addFlashAttribute("successMessage", "Document deleted.");
        return "redirect:/documents";
    }
}
