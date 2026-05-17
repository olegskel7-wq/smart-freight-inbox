package com.smartfreight.inbox.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient bean configuration for calling external services.
 *
 * Creates two separate WebClient beans:
 * — {@code ollamaWebClient} — for the Ollama AI server (long timeouts due to LLM)
 * — {@code portServiceWebClient} — for port-service (short timeouts — fast REST API)
 */
@Configuration
public class WebClientConfig {

    /** Ollama server URL. Default: http://localhost:11434 (local development environment). */
    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    /** External port status service URL. */
    @Value("${port.service.url:http://localhost:9000}")
    private String portServiceUrl;

    /** API key for authenticating with port-service. Passed in the X-API-Key header. */
    @Value("${port.service.api-key}")
    private String portServiceApiKey;

    /**
     * WebClient for Ollama AI server requests.
     *
     * Used by {@link com.smartfreight.inbox.service.AiExtractionService} and
     * {@link com.smartfreight.inbox.service.AiDraftingService}.
     *
     * Timeouts configured for LLM characteristics:
     * — Read timeout: 150 s — LLM may take long for large texts
     * — Write timeout: 30 s — request sending
     * — TCP connection timeout: 10 s
     *
     * ConnectionProvider manages the connection pool (up to 10 concurrent connections to Ollama).
     *
     * @return configured ollamaWebClient
     */
    @Bean
    public WebClient ollamaWebClient() {
        ConnectionProvider provider = ConnectionProvider.builder("ollama-pool")
                .maxConnections(10)
                .maxIdleTime(Duration.ofMinutes(10))
                .maxLifeTime(Duration.ofMinutes(20))
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .build();

        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(150, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * WebClient for port-service requests.
     *
     * Short timeouts — port-service is a fast REST API, not an LLM.
     * Separate bean so that ollamaWebClient timeouts do not apply here.
     *
     * @return configured portServiceWebClient
     */
    @Bean
    public WebClient portServiceWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(10, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(5, TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .baseUrl(portServiceUrl)
                .defaultHeader("X-API-Key", portServiceApiKey)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
