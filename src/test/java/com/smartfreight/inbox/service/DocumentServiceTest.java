package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock private TenantDocumentRepository repository;

    private DocumentService service;

    @BeforeEach
    void setUp() {
        service = new DocumentService(repository);
        lenient().when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void store_keepsTheFileAndTrimsTheIdentifier_signedByTheOperator() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("op@example.com", null, List.of()));
        MockMultipartFile file = new MockMultipartFile("file", "pod.pdf", "application/pdf", new byte[]{1, 2, 3});

        service.store("  MSCU1234567 ", DocumentType.POD, file);

        ArgumentCaptor<TenantDocument> saved = ArgumentCaptor.forClass(TenantDocument.class);
        verify(repository).save(saved.capture());
        TenantDocument doc = saved.getValue();
        assertThat(doc.getIdentifier()).isEqualTo("MSCU1234567");
        assertThat(doc.getDocType()).isEqualTo(DocumentType.POD);
        assertThat(doc.getFilename()).isEqualTo("pod.pdf");
        assertThat(doc.getContentType()).isEqualTo("application/pdf");
        assertThat(doc.getContent()).containsExactly(1, 2, 3);
        assertThat(doc.getFileSize()).isEqualTo(3L);
        assertThat(doc.getUploadedBy()).isEqualTo("op@example.com");
    }

    @Test
    void store_withoutAPrincipal_recordsUnknownUploader() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", null, null, new byte[]{9});

        service.store("ID", DocumentType.OTHER, file);

        ArgumentCaptor<TenantDocument> saved = ArgumentCaptor.forClass(TenantDocument.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUploadedBy()).isEqualTo("unknown");
    }

    @Test
    void deleteIfPresent_deletesOnlyADocumentTheTenantHas() {
        when(repository.existsById(5L)).thenReturn(true);
        when(repository.existsById(6L)).thenReturn(false);

        service.deleteIfPresent(5L);
        service.deleteIfPresent(6L);

        verify(repository).deleteById(5L);
        verify(repository, never()).deleteById(6L);
    }
}
