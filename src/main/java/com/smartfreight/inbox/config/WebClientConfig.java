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
 * <p>Holds the {@code portServiceWebClient} bean. Ollama clients are NOT a singleton bean
 * any more — {@link com.smartfreight.inbox.service.OllamaClientProvider} builds and caches
 * one per resolved endpoint (the per-tenant override arrived with Phase 2.2 S3).
 */
@Configuration
public class WebClientConfig {

    /** External port status service URL. */
    @Value("${port.service.url:http://localhost:9000}")
    private String portServiceUrl;

    /** API key for authenticating with port-service. Passed in the X-API-Key header. */
    @Value("${port.service.api-key}")
    private String portServiceApiKey;

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

    /**
     * WebClient for outbound webhook delivery (Phase 3.5).
     *
     * <p>No {@code baseUrl} — each delivery targets a different tenant-supplied URL, passed per request
     * by {@link com.smartfreight.inbox.webhook.WebhookDispatcher}. Timeouts are short and bounded: the
     * dispatcher blocks on the call while holding a row lock, so a slow receiver must fail fast rather
     * than stall the batch (the dispatcher's own {@code block(timeout)} is the hard ceiling on top of these).
     */
    @Bean
    public WebClient webhookWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(10, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(5, TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
