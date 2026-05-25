package com.smartfreight.port_service.service;

import com.smartfreight.port_service.dto.ContainerResponse;
import com.smartfreight.port_service.repository.ContainerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Business logic for working with containers.
 *
 * The service is separated from the controller following the Single Responsibility Principle (SRP):
 * – The controller handles HTTP requests (routing, serialization)
 * – The service encapsulates business logic (normalization, transformation, validation)
 *
 * This allows the business logic to be reused across:
 * – REST API via ContainerController
 * – Integration with other microservices
 * – Batch processing (if needed)
 */
@Service
public class ContainerService {

    private static final Logger log = LoggerFactory.getLogger(ContainerService.class);

    private final ContainerRepository containerRepository;

    public ContainerService(ContainerRepository containerRepository) {
        this.containerRepository = containerRepository;
    }

    /**
     * Find a container by number and convert it to a DTO.
     *
     * Process:
     * 1. Input validation (null, blank string)
     * 2. Normalization: trim() and toUpperCase() – ISO numbers are standardized
     * 3. Database lookup via the repository
     * 4. If found – Entity transformation to DTO via ContainerResponse.from()
     * 5. Action logging (for audit and debugging)
     *
     * Used by the inbox application over HTTP:
     * {@code inbox → ContainerLookupService → WebClient.get(/api/v1/containers/{number}) → port-service}
     *
     * @param containerNumber the container number (may be null, blank, or mixed case)
     * @return Optional.of(DTO) if the container is found, Optional.empty() otherwise
     */
    public Optional<ContainerResponse> findByNumber(String containerNumber) {
        if (containerNumber == null || containerNumber.isBlank()) {
            log.warn("Container lookup called with blank number");
            return Optional.empty();
        }

        String normalized = containerNumber.trim().toUpperCase();

        return containerRepository.findByContainerNumber(normalized)
                .map(c -> {
                    log.info("Container found: {} | status={} | location={}",
                            c.getContainerNumber(), c.getStatus(), c.getLocation());
                    return ContainerResponse.from(c);
                });
    }
}
