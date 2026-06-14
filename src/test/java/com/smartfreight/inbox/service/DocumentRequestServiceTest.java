package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentRequestService} — the tolerant parse of the model's answer and the
 * stored-document lookup. The Ollama call itself is not exercised here (no WebClient).
 */
@ExtendWith(MockitoExtension.class)
class DocumentRequestServiceTest {

    @Mock
    private TenantDocumentRepository documentRepository;

    @InjectMocks
    private DocumentRequestService service;

    // ---- parseType: tolerant of small-model drift -------------------------------------

    @Test
    void parseType_exactName_matches() {
        assertThat(DocumentRequestService.parseType("POD")).isEqualTo(DocumentType.POD);
        assertThat(DocumentRequestService.parseType("BILL_OF_LADING")).isEqualTo(DocumentType.BILL_OF_LADING);
        assertThat(DocumentRequestService.parseType("CMR")).isEqualTo(DocumentType.CMR);
    }

    @Test
    void parseType_spacedForm_matches() {
        assertThat(DocumentRequestService.parseType("bill of lading")).isEqualTo(DocumentType.BILL_OF_LADING);
        assertThat(DocumentRequestService.parseType("packing list")).isEqualTo(DocumentType.PACKING_LIST);
    }

    @Test
    void parseType_label_matches() {
        assertThat(DocumentRequestService.parseType("Proof of Delivery")).isEqualTo(DocumentType.POD);
        assertThat(DocumentRequestService.parseType("Invoice Copy")).isEqualTo(DocumentType.INVOICE_COPY);
    }

    @Test
    void parseType_sentenceAround_picksFirstMentioned() {
        assertThat(DocumentRequestService.parseType("The customer wants the CUSTOMS clearance papers."))
                .isEqualTo(DocumentType.CUSTOMS);
    }

    @Test
    void parseType_unrecognisedOrBlank_defaultsToOther() {
        assertThat(DocumentRequestService.parseType("something else")).isEqualTo(DocumentType.OTHER);
        assertThat(DocumentRequestService.parseType("OTHER")).isEqualTo(DocumentType.OTHER);
        assertThat(DocumentRequestService.parseType("")).isEqualTo(DocumentType.OTHER);
        assertThat(DocumentRequestService.parseType(null)).isEqualTo(DocumentType.OTHER);
    }

    // ---- findDocument -----------------------------------------------------------------

    @Test
    void findDocument_blankIdentifier_returnsEmpty() {
        assertThat(service.findDocument("  ", DocumentType.POD)).isEmpty();
        assertThat(service.findDocument(null, DocumentType.POD)).isEmpty();
    }

    @Test
    void findDocument_delegatesToRepository() {
        TenantDocument doc = new TenantDocument();
        doc.setId(3L);
        when(documentRepository.findFirstByIdentifierAndDocTypeOrderByUploadedAtDesc("MSCU1234567", DocumentType.POD))
                .thenReturn(Optional.of(doc));

        assertThat(service.findDocument("MSCU1234567", DocumentType.POD)).contains(doc);
    }
}
