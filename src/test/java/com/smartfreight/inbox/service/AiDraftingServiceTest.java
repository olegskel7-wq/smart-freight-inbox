package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TrackedItem;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiDraftingServiceTest {

    @Mock private WebClient webClient;
    @Mock private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private WebClient.RequestBodySpec requestBodySpec;
    @Mock private WebClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;
    @Mock private SettingsService settingsService;
    @Mock private OllamaClientProvider ollamaClients;

    private AiDraftingService service;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        // Return the code-level default prompt so behaviour matches the un-customised pipeline.
        lenient().when(settingsService.getString(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        // Real (default-config) breaker per test — stays CLOSED unless a test opens it.
        // Phase 2.2 S3: the service asks the provider per call; hand back the test doubles.
        circuitBreaker = CircuitBreaker.ofDefaults("ollama-test");
        lenient().when(ollamaClients.client()).thenReturn(webClient);
        lenient().when(ollamaClients.circuitBreaker()).thenReturn(circuitBreaker);
        service = new AiDraftingService(ollamaClients, settingsService,
                new SimpleMeterRegistry(), "llama3.1:8b");
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

    private TrackedItem buildItem(String identifier) {
        TrackedItem c = new TrackedItem();
        c.setIdentifier(identifier);
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

        String result = service.generateDraft("Any updates on MSCU1234567?", buildItem("MSCU1234567"));

        assertEquals(aiDraft, result);
    }

    // Сценарий 2: Ollama недоступен — fallback-черновик содержит данные контейнера из БД
    @Test
    void generateDraft_ollamaFails_fallbackContainsContainerData() {
        stubOllamaError();

        String result = service.generateDraft("Any updates on MSCU1234567?", buildItem("MSCU1234567"));

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

    // Сценарий 4 (Phase 1.7): breaker ОТКРЫТ — исключение пробрасывается (без fallback-драфта),
    // пайплайн припаркует тикет в RETRY_LATER; HTTP-вызов к Ollama не делается
    @Test
    void generateDraft_breakerOpen_propagatesCallNotPermitted() {
        circuitBreaker.transitionToOpenState();

        assertThrows(CallNotPermittedException.class,
                () -> service.generateDraft("Any updates on MSCU1234567?", buildItem("MSCU1234567")));

        verifyNoInteractions(webClient);
    }
}
