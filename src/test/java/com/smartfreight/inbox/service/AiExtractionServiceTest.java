package com.smartfreight.inbox.service;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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

    private AiExtractionService service;

    @BeforeEach
    void setUp() {
        // Return the code-level default prompt so behaviour matches the un-customised pipeline.
        lenient().when(settingsService.getString(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        service = new AiExtractionService(webClient, settingsService, new SimpleMeterRegistry(), "qwen2.5:7b");
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

        String result = service.extractContainerNumber("Please check container MSCU1234567");

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

        String result = service.extractContainerNumber("Please check container MSCU1234567");

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

        String result = service.extractContainerNumber("Hi, any update on HLXU8764321 please?");

        assertEquals("HLXU8764321", result);
    }

    /**
     * Сценарий 4: Тело письма пусто.
     * 
     * Ожидание: возвращается пустая строка без вызова Ollama (оптимизация)
     */
    @Test
    void extractContainerNumber_emptyBody_returnsEmpty() {
        String result = service.extractContainerNumber("   ");

        assertEquals("", result);
    }
}
