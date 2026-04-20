package com.smartfreight.inbox.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiExtractionServiceTest {

    @Mock private WebClient webClient;
    @Mock private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private WebClient.RequestBodySpec requestBodySpec;
    @Mock private WebClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;

    private AiExtractionService service;

    @BeforeEach
    void setUp() {
        service = new AiExtractionService(webClient);
        ReflectionTestUtils.setField(service, "extractionModel", "qwen2.5:7b");
    }

    // Настраивает цепочку WebClient на возврат нужного ответа от Ollama
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubOllama(Map<String, Object> responseBody) {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(responseBody));
    }

    // Настраивает цепочку WebClient на выброс исключения (Ollama недоступен)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubOllamaError() {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.error(new RuntimeException("Connection refused")));
    }

    // Сценарий 1: Ollama вернул чистый номер контейнера — используем его напрямую
    @Test
    void extractContainerNumber_ollamaReturnsCleanNumber_returnsIt() {
        stubOllama(Map.of("response", "MSCU1234567", "done", true));

        String result = service.extractContainerNumber("Please check container MSCU1234567");

        assertEquals("MSCU1234567", result);
    }

    // Сценарий 2: Ollama вернул номер внутри предложения — regex находит его в ответе LLM
    @Test
    void extractContainerNumber_ollamaReturnsSentence_regexExtractsFromResponse() {
        stubOllama(Map.of("response", "The container number mentioned is MSCU1234567.", "done", true));

        String result = service.extractContainerNumber("Please check container MSCU1234567");

        assertEquals("MSCU1234567", result);
    }

    // Сценарий 3: Ollama упал с ошибкой — regex fallback ищет номер прямо в теле письма
    @Test
    void extractContainerNumber_ollamaFails_regexFallbackFindsNumberInEmailBody() {
        stubOllamaError();

        String result = service.extractContainerNumber("Hi, any update on HLXU8764321 please?");

        assertEquals("HLXU8764321", result);
    }

    // Сценарий 4: Тело письма пустое — возвращаем пустую строку без вызова Ollama
    @Test
    void extractContainerNumber_emptyBody_returnsEmpty() {
        String result = service.extractContainerNumber("   ");

        assertEquals("", result);
    }
}
