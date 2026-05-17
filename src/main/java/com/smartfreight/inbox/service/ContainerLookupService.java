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
 * Container data lookup in the external port-service via HTTP.
 *
 * Simulates a real integration with a port cargo management system (TOS/CMS).
 * If port-service is unavailable or the container is not found,
 * {@link Optional#empty()} is returned and the AI will generate a draft without data.
 */
@Service
public class ContainerLookupService {

    private static final Logger log = LoggerFactory.getLogger(ContainerLookupService.class);

    private final WebClient portServiceWebClient;

    public ContainerLookupService(@Qualifier("portServiceWebClient") WebClient portServiceWebClient) {
        this.portServiceWebClient = portServiceWebClient;
    }

    /**
     * Looks up a container in the port-service system by container number.
     *
     * The container number is normalised (trim + toUpperCase) before the request.
     * 4xx responses (container not found) are handled silently — an empty Optional is returned.
     * Network errors (timeout, 5xx) also return an empty Optional —
     * the AI pipeline must work even when port-service is unavailable.
     *
     * @param containerNumber ISO 6346 container number (e.g. MSCU1234567)
     * @return container data if found, or an empty Optional
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
