package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

// Получает данные о контейнере из внешнего port-service через HTTP.
// Это имитирует реальную интеграцию с портовой системой управления грузами (TOS/CMS).
// При недоступности port-service возвращает Optional.empty() — AI сгенерирует черновик без данных.
@Service
public class ContainerLookupService {

    private static final Logger log = LoggerFactory.getLogger(ContainerLookupService.class);

    private final WebClient portServiceWebClient;

    public ContainerLookupService(@Qualifier("portServiceWebClient") WebClient portServiceWebClient) {
        this.portServiceWebClient = portServiceWebClient;
    }

    public Optional<Container> findByNumber(String containerNumber) {
        if (containerNumber == null || containerNumber.isBlank()) {
            log.warn("Container lookup called with empty number");
            return Optional.empty();
        }

        String normalized = containerNumber.trim().toUpperCase();

        try {
            Container container = portServiceWebClient.get()
                    .uri("/api/containers/{number}", normalized)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, response -> {
                        log.warn("Container not found in port-service: {}", normalized);
                        return response.createException();
                    })
                    .bodyToMono(Container.class)
                    .block();

            if (container != null) {
                log.info("Container found via port-service: {} | status={} | location={}",
                        container.getContainerNumber(), container.getStatus(), container.getLocation());
                return Optional.of(container);
            }
            return Optional.empty();

        } catch (Exception e) {
            log.warn("Port-service unavailable or container not found '{}': {}", normalized, e.getMessage());
            return Optional.empty();
        }
    }
}
