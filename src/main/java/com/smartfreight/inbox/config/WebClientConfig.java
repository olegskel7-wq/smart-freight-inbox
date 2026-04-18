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

// @Configuration — Spring конфигурация, создаёт бины
@Configuration
public class WebClientConfig {

    // URL Ollama (локального AI сервера)
    // Значение по умолчанию: http://localhost:11434
    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    // URL внешнего сервиса портовых статусов
    @Value("${port.service.url:http://localhost:9000}")
    private String portServiceUrl;

    // WebClient bean для HTTP запросов к Ollama
    // Используется в AiExtractionService и AiDraftingService (Phase 2)
    @Bean
    public WebClient ollamaWebClient() {

        // ConnectionProvider — управляет пулом HTTP соединений
        // Переиспользует существующие соединения вместо создания новых на каждый запрос
        ConnectionProvider provider = ConnectionProvider.builder("ollama-pool")
                // До 10 одновременных соединений к Ollama
                .maxConnections(10)
                // Неактивное соединение закрывается через 10 минут
                .maxIdleTime(Duration.ofMinutes(10))
                // Соединение живёт максимум 20 минут, потом пересоздаётся
                .maxLifeTime(Duration.ofMinutes(20))
                // Если все соединения заняты — ждём свободное максимум 10 секунд
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .build();

        // HttpClient — низкоуровневый клиент на Netty
        // Настраиваем таймауты с учётом особенностей LLM
        HttpClient httpClient = HttpClient.create(provider)
                // Таймаут установки TCP соединения: 10 сек
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)

                // Настройка после установки соединения
                .doOnConnected(conn -> conn
                        // Таймаут получения ответа: 150 сек
                        // LLM может ответить долго, особенно для больших текстов
                        .addHandlerLast(new ReadTimeoutHandler(150, TimeUnit.SECONDS))

                        // Таймаут отправки запроса: 30 сек
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS))
                );

        // Собираем WebClient
        return WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    // WebClient для запросов к port-service (быстрый, короткие таймауты — это не LLM)
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
