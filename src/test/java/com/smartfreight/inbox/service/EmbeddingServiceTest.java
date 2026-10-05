package com.smartfreight.inbox.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the real {@link EmbeddingService} WebClient against a scripted Ollama embeddings endpoint
 * ({@link MockWebServer}), with a stubbed {@link OllamaClientProvider} handing it the client +
 * a default circuit breaker. Verifies parsing and graceful degradation.
 */
class EmbeddingServiceTest {

    private MockWebServer server;
    private OllamaClientProvider provider;
    private EmbeddingService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/").toString().replaceAll("/$", "");

        provider = mock(OllamaClientProvider.class);
        when(provider.client()).thenReturn(WebClient.builder().baseUrl(baseUrl).build());
        when(provider.circuitBreaker()).thenReturn(CircuitBreaker.ofDefaults("test-embed"));

        service = new EmbeddingService(provider, "nomic-embed-text");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    @Test
    void embed_parsesVector() {
        server.enqueue(json("{\"embedding\":[0.1,0.2,0.3]}"));

        Optional<float[]> result = service.embed("where is my container?");

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactly(0.1f, 0.2f, 0.3f);
        verify(provider).recordUsage(eq("embedding"), eq("nomic-embed-text"), any());
    }

    @Test
    void embed_blankText_returnsEmpty_withoutCallingOllama() {
        Optional<float[]> result = service.embed("   ");

        assertThat(result).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void embed_serverError_degradesToEmpty() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThat(service.embed("hello")).isEmpty();
    }

    @Test
    void embed_emptyVector_returnsEmpty() {
        server.enqueue(json("{\"embedding\":[]}"));

        assertThat(service.embed("hello")).isEmpty();
    }

    @Test
    void toVectorLiteral_formatsAsPgvector() {
        assertThat(EmbeddingService.toVectorLiteral(new float[]{0.5f, -0.25f, 1.0f}))
                .isEqualTo("[0.5,-0.25,1.0]");
    }
}
