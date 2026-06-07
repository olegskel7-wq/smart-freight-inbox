package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Container;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
public class ContainerLookupService implements ItemLookupService {

    private static final Logger log = LoggerFactory.getLogger(ContainerLookupService.class);

    private final WebClient portServiceWebClient;
    private final Counter hitCounter;
    private final Counter missCounter;

    public ContainerLookupService(@Qualifier("portServiceWebClient") WebClient portServiceWebClient,
                                  MeterRegistry meterRegistry) {
        this.portServiceWebClient = portServiceWebClient;
        this.hitCounter = Counter.builder("smartfreight_container_lookup_total")
                .description("Container lookups in port-service")
                .tag("result", "found")
                .register(meterRegistry);
        this.missCounter = Counter.builder("smartfreight_container_lookup_total")
                .description("Container lookups in port-service")
                .tag("result", "not_found")
                .register(meterRegistry);
    }

    /**
     * Looks up a container in the port-service system by container number.
     *
     * The container number is normalised (trim + toUpperCase) before the request.
     * 4xx responses (container not found) are handled silently — an empty Optional is returned.
     * Network errors (timeout, 5xx) also return an empty Optional —
     * the AI pipeline must work even when port-service is unavailable.
     *
     * @param identifier ISO 6346 container number (e.g. MSCU1234567)
     * @return container data if found, or an empty Optional
     */
    @Override
    public Optional<Container> findByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            log.warn("Container lookup called with empty number");
            return Optional.empty();
        }

        String normalized = identifier.trim().toUpperCase();

        try {
            Container container = portServiceWebClient.get()
                    .uri("/api/v1/containers/{number}", normalized)
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
                hitCounter.increment();
                return Optional.of(container);
            }
            missCounter.increment();
            return Optional.empty();

        } catch (Exception e) {
            log.warn("Port-service unavailable or container not found '{}': {}", normalized, e.getMessage());
            missCounter.increment();
            return Optional.empty();
        }
    }
}
