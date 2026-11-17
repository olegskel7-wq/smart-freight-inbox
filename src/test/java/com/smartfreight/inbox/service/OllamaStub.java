package com.smartfreight.inbox.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.mockito.ArgumentCaptor;
import org.mockito.quality.Strictness;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A stubbed Ollama endpoint for service tests: the provider hands out a mocked WebClient whose
 * {@code post().uri().bodyValue().retrieve().bodyToMono(Map)} chain answers what the test sets, plus a
 * real (closed) circuit breaker. The tests assert the request body the service sent and what it did
 * with the answer, so they hold whatever sits between the service and the WebClient.
 */
final class OllamaStub {

    final OllamaClientProvider provider = lenientMock(OllamaClientProvider.class);
    final WebClient webClient = lenientMock(WebClient.class);
    final CircuitBreaker breaker = CircuitBreaker.ofDefaults("ollama-test");

    private final WebClient.RequestBodyUriSpec uriSpec = lenientMock(WebClient.RequestBodyUriSpec.class);
    private final WebClient.RequestBodySpec bodySpec = lenientMock(WebClient.RequestBodySpec.class);
    @SuppressWarnings("rawtypes")
    private final WebClient.RequestHeadersSpec headersSpec = lenientMock(WebClient.RequestHeadersSpec.class);
    private final WebClient.ResponseSpec responseSpec = lenientMock(WebClient.ResponseSpec.class);

    @SuppressWarnings("unchecked")
    OllamaStub() {
        when(provider.client()).thenReturn(webClient);
        when(provider.circuitBreaker()).thenReturn(breaker);
        when(webClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
    }

    /** Ollama answers this JSON body; {@code null} = an empty body. */
    void answers(Map<String, Object> response) {
        when(responseSpec.bodyToMono(Map.class)).thenReturn(response == null ? Mono.empty() : Mono.just(response));
    }

    /** The call fails with this error (connection refused, timeout, 500…). */
    void fails(RuntimeException error) {
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.error(error));
    }

    /** The path the service posted to. */
    String sentPath() {
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(uriSpec).uri(path.capture());
        return path.getValue();
    }

    /** The JSON body the service sent. */
    Map<?, ?> sentBody() {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec).bodyValue(body.capture());
        return (Map<?, ?>) body.getValue();
    }

    /** Lenient: a test that never reaches the call (blank input, open breaker) leaves the chain unused. */
    private static <T> T lenientMock(Class<T> type) {
        return mock(type, withSettings().strictness(Strictness.LENIENT));
    }
}
