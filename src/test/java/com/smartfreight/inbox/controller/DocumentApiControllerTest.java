package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.DocumentService;
import com.smartfreight.inbox.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for the JSON documents API (Phase 3.2). No security/tenant filter here
 * (covered by KeycloakJwtRoleConverterTest / TenantFilterTest); this verifies the DTO snapshot
 * (documents + type options), the multipart upload guards, the binary download, and delete.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentApiControllerTest {

    @Mock private DocumentService documentService;
    @Mock private TenantDocumentRepository documentRepository;
    @Mock private SettingsService settingsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TenantDocument doc = new TenantDocument();
        doc.setId(5L);
        doc.setIdentifier("MSCU1234567");
        doc.setDocType(DocumentType.POD);
        doc.setFilename("pod.pdf");
        doc.setContentType("application/pdf");
        doc.setContent(new byte[]{1, 2, 3, 4});
        doc.setFileSize(4L);
        doc.setUploadedAt(LocalDateTime.now());
        when(documentRepository.findAllByOrderByUploadedAtDesc()).thenReturn(List.of(doc));
        when(documentRepository.findById(5L)).thenReturn(Optional.of(doc));
        when(settingsService.isFeatureEnabled(eq(DocumentService.FEATURE_KEY), anyBoolean()))
                .thenReturn(true);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentApiController(
                        documentService, documentRepository, settingsService))
                .build();
    }

    @Test
    void getReturnsSnapshotWithDocumentsAndTypeOptions() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureEnabled").value(true))
                .andExpect(jsonPath("$.documents[0].id").value(5))
                .andExpect(jsonPath("$.documents[0].identifier").value("MSCU1234567"))
                .andExpect(jsonPath("$.documents[0].docType").value("POD"))
                .andExpect(jsonPath("$.documents[0].filename").value("pod.pdf"))
                .andExpect(jsonPath("$.docTypes[0].value").value("POD"))
                .andExpect(jsonPath("$.docTypes[0].label").value("Proof of Delivery"));
    }

    @Test
    void uploadStoresDocumentAndReturnsSnapshot() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "pod.pdf", "application/pdf", new byte[]{1, 2, 3, 4});

        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .param("identifier", "MSCU1234567")
                        .param("docType", "POD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].id").value(5));

        verify(documentService).store(eq("MSCU1234567"), eq(DocumentType.POD), any());
    }

    @Test
    void uploadEmptyFileIsBadRequest() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/api/documents/upload")
                        .file(empty)
                        .param("identifier", "MSCU1234567")
                        .param("docType", "POD"))
                .andExpect(status().isBadRequest());

        verify(documentService, never()).store(any(), any(), any());
    }

    @Test
    void uploadBlankIdentifierIsBadRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "pod.pdf", "application/pdf", new byte[]{1, 2, 3, 4});

        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .param("identifier", "   ")
                        .param("docType", "POD"))
                .andExpect(status().isBadRequest());

        verify(documentService, never()).store(any(), any(), any());
    }

    @Test
    void downloadStreamsFileBytesAsAttachment() throws Exception {
        mockMvc.perform(get("/api/documents/5/download"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"pod.pdf\""))
                .andExpect(content().bytes(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void downloadUnknownIsNotFound() throws Exception {
        when(documentRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/documents/99/download"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesDocumentAndReturnsSnapshot() throws Exception {
        mockMvc.perform(delete("/api/documents/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].id").value(5));

        verify(documentRepository).deleteById(5L);
    }
}
