package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentRequestService} — the tolerant parse of the model's answer and the
 * stored-document lookup, and the detection call against a stubbed Ollama ({@link OllamaStub}).
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

    // ---- detectRequestedType: the Ollama call ------------------------------------------

    /** The service under test over a stubbed Ollama; the stored prompt resolves to the code default. */
    private static DocumentRequestService detector(OllamaStub ollama) {
        SettingsService settings = mock(SettingsService.class, org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        when(settings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        return new DocumentRequestService(new LlmClient(ollama.provider), settings, mock(TenantDocumentRepository.class), "qwen-test");
    }

    @Test
    void detect_sendsTheEnquiryToTheExtractionModel_andParsesTheAnswer() {
        OllamaStub ollama = new OllamaStub();
        ollama.answers(Map.of("response", "Bill of lading", "done", true));

        DocumentType type = detector(ollama).detectRequestedType("Please send the BOL for MSCU1234567");

        assertThat(type).isEqualTo(DocumentType.BILL_OF_LADING);
        assertThat(ollama.sentPath()).isEqualTo("/api/generate");
        Map<?, ?> body = ollama.sentBody();
        assertThat(body.get("model")).isEqualTo("qwen-test");
        assertThat(body.get("stream")).isEqualTo(false);
        assertThat((String) body.get("prompt")).contains("Please send the BOL for MSCU1234567");
        assertThat(body).hasSize(3);
        verify(ollama.provider).recordUsage(eq("document-detect"), eq("qwen-test"), any());
    }

    @Test
    void detect_blankEnquiry_isOtherWithoutACall() {
        OllamaStub ollama = new OllamaStub();

        assertThat(detector(ollama).detectRequestedType("  ")).isEqualTo(DocumentType.OTHER);
        assertThat(detector(ollama).detectRequestedType(null)).isEqualTo(DocumentType.OTHER);

        verifyNoInteractions(ollama.webClient);
    }

    @Test
    void detect_emptyOllamaBody_isOther_andStillMetered() {
        OllamaStub ollama = new OllamaStub();
        ollama.answers(null);

        assertThat(detector(ollama).detectRequestedType("Send the POD")).isEqualTo(DocumentType.OTHER);

        verify(ollama.provider).recordUsage("document-detect", "qwen-test", null);
    }

    @Test
    void detect_failedCall_isOther_andNotMetered() {
        OllamaStub ollama = new OllamaStub();
        ollama.fails(new RuntimeException("Connection refused"));

        assertThat(detector(ollama).detectRequestedType("Send the POD")).isEqualTo(DocumentType.OTHER);

        verify(ollama.provider, never()).recordUsage(any(), any(), any());
    }

    @Test
    void detect_openBreaker_propagates_withoutACall() {
        OllamaStub ollama = new OllamaStub();
        ollama.breaker.transitionToOpenState();

        assertThatThrownBy(() -> detector(ollama).detectRequestedType("Send the POD"))
                .isInstanceOf(CallNotPermittedException.class);

        verifyNoInteractions(ollama.webClient);
    }
}
