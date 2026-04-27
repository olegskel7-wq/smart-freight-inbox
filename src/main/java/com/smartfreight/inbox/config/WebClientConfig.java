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
 * WebClient bean'ų konfigūracija išorinių paslaugų kvietimams.
 *
 * Sukuria du atskirus WebClient bean'us:
 * – {@code ollamaWebClient} – Ollama AI serveriui (ilgi laiko limitai dėl LLM)
 * – {@code portServiceWebClient} – port-service (trumpi laiko limitai – greitas REST API)
 */
@Configuration
public class WebClientConfig {

    /** Ollama serverio URL. Numatytasis: http://localhost:11434 (lokali kūrimo aplinka). */
    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    /** Išorinio portų statuso paslaugos URL. */
    @Value("${port.service.url:http://localhost:9000}")
    private String portServiceUrl;

    /**
     * WebClient Ollama AI serverio užklausoms.
     *
     * Naudojamas {@link com.smartfreight.inbox.service.AiExtractionService} ir
     * {@link com.smartfreight.inbox.service.AiDraftingService}.
     *
     * Laiko limitai sukonfigūruoti LLM specifikai:
     * – Skaitymo laiko limitas: 150 s – LLM gali generuoti ilgai dideliems tekstams
     * – Rašymo laiko limitas: 30 s – užklausos siuntimas
     * – TCP jungimosi laiko limitas: 10 s
     *
     * ConnectionProvider valdo ryšių baseiną (iki 10 vienalaikių ryšių su Ollama).
     *
     * @return sukonfigūruotas ollamaWebClient
     */
    @Bean
    public WebClient ollamaWebClient() {
        ConnectionProvider provider = ConnectionProvider.builder("ollama-pool")
                .maxConnections(10)
                .maxIdleTime(Duration.ofMinutes(10))
                .maxLifeTime(Duration.ofMinutes(20))
                // Jei visi ryšiai užimti – laukiame laisvo iki 10 s
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
     * WebClient port-service užklausoms.
     *
     * Trumpi laiko limitai – port-service yra greitas REST API, ne LLM.
     * Atskiras bean'as, kad ollamaWebClient laiko limitai negaliotų čia.
     *
     * @return sukonfigūruotas portServiceWebClient
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
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
