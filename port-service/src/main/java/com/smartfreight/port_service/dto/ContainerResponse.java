package com.smartfreight.port_service.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * DTO (Data Transfer Object) for the REST API response containing container data.
 *
 * The DTO is separated from the JPA entity for several reasons:
 * 1. Encapsulation – public API consumers do not see internal fields (id, version)
 * 2. API cleanliness – the DTO contains only the fields relevant to clients
 * 3. Independence – if the DB schema changes, the API remains stable
 * 4. Security – sensitive DB information (e.g. password hashes) is never exposed in the response
 *
 * Used as:
 * {@code GET /api/containers/MSCU1234567 → ContainerResponse JSON}
 *
 * @param containerNumber the container number (ISO 6346)
 * @param status status in the port system (IN_TRANSIT, AT_PORT, DELIVERED, etc.)
 * @param location current location (port terminal, customs warehouse)
 * @param eta estimated arrival / release time for the customer
 * @param updatedAt timestamp of the last status update
 */
public record ContainerResponse(
        String containerNumber,
        String status,
        String location,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime eta,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime updatedAt
) {
    /**
     * Converts a JPA Container entity to a REST DTO.
     *
     * Pattern: mapping logic lives in a dedicated method rather than in the controller.
     * This simplifies:
     * – Reuse across different call sites (controller, service, tests)
     * – Mapping visibility (it is clear how Entity → DTO)
     * – Testing (the transformation can be tested in isolation)
     *
     * @param c the JPA container entity
     * @return DTO ready for JSON serialization
     */
    public static ContainerResponse from(com.smartfreight.port_service.entity.Container c) {
        return new ContainerResponse(
                c.getContainerNumber(),
                c.getStatus() != null ? c.getStatus().name() : null,
                c.getLocation(),
                c.getEta(),
                c.getUpdatedAt()
        );
    }
}
