package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Container;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerLookupServiceTest {

    @Mock private WebClient webClient;
    @Mock private WebClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;
    @Mock private WebClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;

    private ContainerLookupService service;

    @BeforeEach
    void setUp() {
        service = new ContainerLookupService(webClient);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPortService(Container container) {
        when(webClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString(), (Object) any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Container.class)).thenReturn(Mono.just(container));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPortServiceError() {
        when(webClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString(), (Object) any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Container.class)).thenReturn(Mono.error(new RuntimeException("port-service unavailable")));
    }

    // Сценарий 1: port-service вернул контейнер — возвращаем его
    @Test
    void findByNumber_containerFound_returnsContainer() {
        Container container = new Container();
        container.setContainerNumber("MSCU1234567");
        container.setStatus("AT_PORT");
        container.setLocation("Felixstowe Terminal 2");
        stubPortService(container);

        Optional<Container> result = service.findByNumber("MSCU1234567");

        assertTrue(result.isPresent());
        assertEquals("MSCU1234567", result.get().getContainerNumber());
        assertEquals("AT_PORT", result.get().getStatus());
    }

    // Сценарий 2: Передан пустой номер — возвращаем empty без HTTP-вызова
    @Test
    void findByNumber_emptyNumber_returnsEmpty() {
        Optional<Container> result = service.findByNumber("  ");

        assertFalse(result.isPresent());
    }

    // Сценарий 3: Передан null — возвращаем empty без HTTP-вызова
    @Test
    void findByNumber_nullNumber_returnsEmpty() {
        Optional<Container> result = service.findByNumber(null);

        assertFalse(result.isPresent());
    }

    // Сценарий 4: port-service недоступен — возвращаем empty, не бросаем исключение
    @Test
    void findByNumber_portServiceDown_returnsEmptyGracefully() {
        stubPortServiceError();

        Optional<Container> result = service.findByNumber("MSCU1234567");

        assertFalse(result.isPresent());
    }

    // Сценарий 5: Номер нормализуется к верхнему регистру перед отправкой
    @Test
    void findByNumber_lowercaseInput_normalizedToUppercase() {
        Container container = new Container();
        container.setContainerNumber("MSCU1234567");
        container.setStatus("IN_TRANSIT");
        stubPortService(container);

        Optional<Container> result = service.findByNumber("mscu1234567");

        assertTrue(result.isPresent());
        assertEquals("MSCU1234567", result.get().getContainerNumber());
    }
}
