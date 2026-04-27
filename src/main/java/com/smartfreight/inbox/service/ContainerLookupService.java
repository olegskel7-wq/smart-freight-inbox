package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

/**
 * Konteinerio duomenų paieška išoriniame port-service per HTTP.
 *
 * Imituoja realią integraciją su uosto krovinių valdymo sistema (TOS/CMS).
 * Jei port-service nepasiekiamas arba konteineris nerastas –
 * grąžinamas {@link Optional#empty()}, ir AI sugeneruos juodraštį be duomenų.
 */
@Service
public class ContainerLookupService {

    private static final Logger log = LoggerFactory.getLogger(ContainerLookupService.class);

    private final WebClient portServiceWebClient;

    public ContainerLookupService(@Qualifier("portServiceWebClient") WebClient portServiceWebClient) {
        this.portServiceWebClient = portServiceWebClient;
    }

    /**
     * Ieško konteinerio port-service sistemoje pagal konteinerio numerį.
     *
     * Konteinerio numeris normalizuojamas (trim + toUpperCase) prieš užklausą.
     * 4xx atsakymai (konteineris nerastas) apdorojami tyliai – grąžinamas tuščias Optional.
     * Tinklo klaidos (timeout, 5xx) taip pat grąžina tuščią Optional –
     * AI konvejeris turi veikti net kai port-service nepasiekiamas.
     *
     * @param containerNumber ISO 6346 konteinerio numeris (pvz.: MSCU1234567)
     * @return konteinerio duomenys jei rasti, arba tuščias Optional
     */
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
