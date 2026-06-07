package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TrackedItem;
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
 * Freight implementation of {@link ItemLookupService} — looks up tracked-item data in the external
 * port-service over HTTP.
 *
 * Simulates a real integration with a port cargo management system (TOS/CMS). For a different
 * vertical a different {@link ItemLookupService} implementation would be wired in instead; the
 * pipeline ({@link TicketProcessingService}) is unaware of this concrete backend.
 *
 * If port-service is unavailable or the item is not found, {@link Optional#empty()} is returned and
 * the AI will generate a draft without data.
 */
@Service
public class PortServiceItemLookup implements ItemLookupService {

    private static final Logger log = LoggerFactory.getLogger(PortServiceItemLookup.class);

    private final WebClient portServiceWebClient;
    private final Counter hitCounter;
    private final Counter missCounter;

    public PortServiceItemLookup(@Qualifier("portServiceWebClient") WebClient portServiceWebClient,
                                 MeterRegistry meterRegistry) {
        this.portServiceWebClient = portServiceWebClient;
        // Metric name kept as smartfreight_container_lookup_total for Grafana dashboard stability.
        this.hitCounter = Counter.builder("smartfreight_container_lookup_total")
                .description("Item lookups in port-service")
                .tag("result", "found")
                .register(meterRegistry);
        this.missCounter = Counter.builder("smartfreight_container_lookup_total")
                .description("Item lookups in port-service")
                .tag("result", "not_found")
                .register(meterRegistry);
    }

    /**
     * Looks up a tracked item in the port-service system by its identifier.
     *
     * The identifier is normalised (trim + toUpperCase) before the request.
     * 4xx responses (item not found) are handled silently — an empty Optional is returned.
     * Network errors (timeout, 5xx) also return an empty Optional —
     * the AI pipeline must work even when port-service is unavailable.
     *
     * @param identifier ISO 6346 container number (e.g. MSCU1234567)
     * @return item data if found, or an empty Optional
     */
    @Override
    public Optional<TrackedItem> findByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            log.warn("Item lookup called with empty identifier");
            return Optional.empty();
        }

        String normalized = identifier.trim().toUpperCase();

        try {
            TrackedItem item = portServiceWebClient.get()
                    .uri("/api/v1/containers/{number}", normalized)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, response -> {
                        log.warn("Item not found in port-service: {}", normalized);
                        return response.createException();
                    })
                    .bodyToMono(TrackedItem.class)
                    .block();

            if (item != null) {
                log.info("Item found via port-service: {} | status={} | location={}",
                        item.getIdentifier(), item.getStatus(), item.getLocation());
                hitCounter.increment();
                return Optional.of(item);
            }
            missCounter.increment();
            return Optional.empty();

        } catch (Exception e) {
            log.warn("Port-service unavailable or item not found '{}': {}", normalized, e.getMessage());
            missCounter.increment();
            return Optional.empty();
        }
    }
}
