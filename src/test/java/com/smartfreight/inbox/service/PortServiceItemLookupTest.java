package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TrackedItem;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortServiceItemLookupTest {

    @Mock private WebClient webClient;
    @Mock private WebClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;
    @Mock private WebClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;

    private PortServiceItemLookup service;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        // Real (default-config) breaker per test — stays CLOSED unless a test opens it.
        circuitBreaker = CircuitBreaker.ofDefaults("portService-test");
        service = new PortServiceItemLookup(webClient, circuitBreaker, new SimpleMeterRegistry());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPortService(TrackedItem item) {
        when(webClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString(), (Object) any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(TrackedItem.class)).thenReturn(Mono.just(item));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPortServiceError() {
        when(webClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString(), (Object) any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(TrackedItem.class)).thenReturn(Mono.error(new RuntimeException("port-service unavailable")));
    }

    // Сценарий 1: port-service вернул item — возвращаем его
    @Test
    void findByIdentifier_itemFound_returnsItem() {
        TrackedItem item = new TrackedItem();
        item.setIdentifier("MSCU1234567");
        item.setStatus("AT_PORT");
        item.setLocation("Felixstowe Terminal 2");
        stubPortService(item);

        Optional<TrackedItem> result = service.findByIdentifier("MSCU1234567");

        assertTrue(result.isPresent());
        assertEquals("MSCU1234567", result.get().getIdentifier());
        assertEquals("AT_PORT", result.get().getStatus());
    }

    // Сценарий 2: Передан пустой идентификатор — возвращаем empty без HTTP-вызова
    @Test
    void findByIdentifier_emptyIdentifier_returnsEmpty() {
        Optional<TrackedItem> result = service.findByIdentifier("  ");

        assertFalse(result.isPresent());
    }

    // Сценарий 3: Передан null — возвращаем empty без HTTP-вызова
    @Test
    void findByIdentifier_nullIdentifier_returnsEmpty() {
        Optional<TrackedItem> result = service.findByIdentifier(null);

        assertFalse(result.isPresent());
    }

    // Сценарий 4: port-service недоступен — возвращаем empty, не бросаем исключение
    @Test
    void findByIdentifier_portServiceDown_returnsEmptyGracefully() {
        stubPortServiceError();

        Optional<TrackedItem> result = service.findByIdentifier("MSCU1234567");

        assertFalse(result.isPresent());
    }

    // Сценарий 4b (Phase 1.7): breaker ОТКРЫТ — empty сразу, HTTP-вызов не делается.
    // В отличие от Ollama-сервисов исключение НЕ пробрасывается: драфт без данных —
    // штатная деградация lookup'а, тикет не паркуется
    @Test
    void findByIdentifier_breakerOpen_returnsEmptyWithoutHttpCall() {
        circuitBreaker.transitionToOpenState();

        Optional<TrackedItem> result = service.findByIdentifier("MSCU1234567");

        assertFalse(result.isPresent());
        verifyNoInteractions(webClient);
    }

    // Сценарий 5: Идентификатор нормализуется к верхнему регистру перед отправкой
    @Test
    void findByIdentifier_lowercaseInput_normalizedToUppercase() {
        TrackedItem item = new TrackedItem();
        item.setIdentifier("MSCU1234567");
        item.setStatus("IN_TRANSIT");
        stubPortService(item);

        Optional<TrackedItem> result = service.findByIdentifier("mscu1234567");

        assertTrue(result.isPresent());
        assertEquals("MSCU1234567", result.get().getIdentifier());
    }
}
