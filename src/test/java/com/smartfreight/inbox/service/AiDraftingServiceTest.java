package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Container;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiDraftingServiceTest {

    @Mock private WebClient webClient;
    @Mock private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private WebClient.RequestBodySpec requestBodySpec;
    @Mock private WebClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;

    private AiDraftingService service;

    @BeforeEach
    void setUp() {
        service = new AiDraftingService(webClient);
        ReflectionTestUtils.setField(service, "draftingModel", "llama3.1:8b");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubOllama(Map<String, Object> responseBody) {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(responseBody));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubOllamaError() {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.error(new RuntimeException("Ollama unavailable")));
    }

    private Container buildContainer(String number) {
        Container c = new Container();
        c.setContainerNumber(number);
        c.setStatus("AT_PORT");
        c.setLocation("Felixstowe Terminal 2");
        c.setEta(LocalDateTime.of(2026, 4, 25, 10, 0));
        return c;
    }

    // Сценарий 1: Ollama вернул готовый черновик — возвращаем его оператору
    @Test
    void generateDraft_ollamaReturnsDraft_returnsDraft() {
        String aiDraft = "Dear customer, your container MSCU1234567 is at Felixstowe Terminal 2.";
        stubOllama(Map.of("response", aiDraft, "done", true));

        String result = service.generateDraft("Any updates on MSCU1234567?", buildContainer("MSCU1234567"));

        assertEquals(aiDraft, result);
    }

    // Сценарий 2: Ollama недоступен — fallback-черновик содержит данные контейнера из БД
    @Test
    void generateDraft_ollamaFails_fallbackContainsContainerData() {
        stubOllamaError();

        String result = service.generateDraft("Any updates on MSCU1234567?", buildContainer("MSCU1234567"));

        assertNotNull(result);
        assertFalse(result.isBlank(), "Fallback draft must not be empty");
        assertTrue(result.contains("MSCU1234567"), "Fallback draft must contain container number");
        assertTrue(result.contains("AT_PORT"), "Fallback draft must contain container status");
    }

    // Сценарий 3: Контейнер не найден (null) — fallback сообщает что данных нет
    @Test
    void generateDraft_containerNotFound_fallbackMentionsNotFound() {
        stubOllamaError();

        String result = service.generateDraft("Where is my container?", null);

        assertNotNull(result);
        assertFalse(result.isBlank(), "Fallback draft must not be empty");
        assertTrue(
            result.toLowerCase().contains("unable") || result.toLowerCase().contains("not") ||
            result.toLowerCase().contains("contact"),
            "Fallback for missing container should indicate the problem"
        );
    }
}
