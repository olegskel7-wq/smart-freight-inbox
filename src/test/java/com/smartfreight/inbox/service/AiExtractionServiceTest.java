package com.smartfreight.inbox.service;

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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit тесты для AiExtractionService (извлечение номера контейнера через Qwen 2.5).
 * 
 * Используется Mockito для изоляции от Ollama:
 * - WebClient мокируется (не делается реальный HTTP запрос к Ollama)
 * - Тесты выполняются быстро (<100ms вместо 150+ сек)
 * - Тесты независимы от наличия Ollama на машине
 * 
 * Покрывает сценарии:
 * 1. Ollama вернул чистый номер контейнера
 * 2. Ollama вернул номер внутри предложения (regex извлечение)
 * 3. Ollama упала — fallback regex по телу письма
 * 4. Пустое тело письма
 */
@ExtendWith(MockitoExtension.class)
class AiExtractionServiceTest {

    @Mock private WebClient webClient;
    @Mock private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private WebClient.RequestBodySpec requestBodySpec;
    @Mock private WebClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;
    @Mock private SettingsService settingsService;
    @Mock private OllamaClientProvider ollamaClients;

    private AiExtractionService service;
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
        service = new AiExtractionService(ollamaClients, settingsService,
                new SimpleMeterRegistry(), "qwen2.5:7b");
    }

    /**
     * Настраивает цепочку Mockito для успешного ответа от Ollama.
     * 
     * Mockito использует паттерн "builder" — каждый метод возвращает новый тип:
     * webClient.post() → RequestBodyUriSpec
     * .uri() → RequestBodySpec
     * .bodyValue() → RequestHeadersSpec
     * .retrieve() → ResponseSpec
     * .bodyToMono() → Mono<T>
     * .block() → T (синхронная блокировка результата)
     * 
     * Каждый when() указывает что вернуть на каждом шаге цепочки.
     * 
     * @param responseBody JSON объект как Map (Ollama ответ)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubOllama(Map<String, Object> responseBody) {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(responseBody));
    }

    /**
     * Настраивает Mockito для имитации сбоя Ollama (выброс исключения).
     * 
     * Используется для тестирования fallback логики — когда Ollama недоступна,
     * сервис должен вернуть результат regex без выброса исключения.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubOllamaError() {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.error(new RuntimeException("Connection refused")));
    }

    /**
     * Сценарий 1: Ollama вернул чистый номер контейнера.
     * 
     * Ожидание: сервис возвращает номер напрямую без проверок
     */
    @Test
    void extractContainerNumber_ollamaReturnsCleanNumber_returnsIt() {
        stubOllama(Map.of("response", "MSCU1234567", "done", true));

        String result = service.extractIdentifier("Please check container MSCU1234567");

        assertEquals("MSCU1234567", result);
    }

    /**
     * Сценарий 2: Ollama вернул номер внутри предложения.
     * 
     * Ожидание: regex находит номер в ответе Ollama и возвращает его
     */
    @Test
    void extractContainerNumber_ollamaReturnsSentence_regexExtractsFromResponse() {
        stubOllama(Map.of("response", "The container number mentioned is MSCU1234567.", "done", true));

        String result = service.extractIdentifier("Please check container MSCU1234567");

        assertEquals("MSCU1234567", result);
    }

    /**
     * Сценарий 3: Ollama упала — fallback regex работает.
     * 
     * Ожидание: несмотря на исключение, сервис находит номер в теле письма через regex
     * (защита от отказа сервиса при недоступности Ollama)
     */
    @Test
    void extractContainerNumber_ollamaFails_regexFallbackFindsNumberInEmailBody() {
        stubOllamaError();

        String result = service.extractIdentifier("Hi, any update on HLXU8764321 please?");

        assertEquals("HLXU8764321", result);
    }

    /**
     * Сценарий 4: Тело письма пусто.
     *
     * Ожидание: возвращается пустая строка без вызова Ollama (оптимизация)
     */
    @Test
    void extractContainerNumber_emptyBody_returnsEmpty() {
        String result = service.extractIdentifier("   ");

        assertEquals("", result);
    }

    /**
     * Сценарий 5 (Phase 1.7): circuit breaker ОТКРЫТ — Ollama лежит.
     *
     * Ожидание: CallNotPermittedException пробрасывается (НЕ глотается generic-catch'ем
     * с regex-fallback'ом) — пайплайн припаркует тикет в RETRY_LATER. HTTP-вызов не делается.
     */
    @Test
    void extractContainerNumber_breakerOpen_propagatesCallNotPermitted() {
        circuitBreaker.transitionToOpenState();

        assertThrows(CallNotPermittedException.class,
                () -> service.extractIdentifier("Any update on MSCU1234567?"));

        verifyNoInteractions(webClient);
    }
}
