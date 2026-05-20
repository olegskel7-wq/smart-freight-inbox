package com.smartfreight.port_service.controller;

import com.smartfreight.port_service.dto.ContainerResponse;
import com.smartfreight.port_service.service.ContainerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for container data queries.
 *
 * The port-service microservice exposes a single read-only endpoint:
 * {@code GET /api/containers/{containerNumber}}
 *
 * The main inbox application's ContainerLookupService uses this endpoint
 * to retrieve container status while processing customer email inquiries.
 *
 * Data flow:
 * 1. Customer sends an email: "Where is my container MSCU1234567?"
 * 2. inbox → AiExtractionService extracts the number MSCU1234567
 * 3. inbox → ContainerLookupService sends: GET /api/containers/MSCU1234567
 * 4. port-service returns JSON with the current status
 * 5. inbox → AiDraftingService generates a reply based on the data
 * 6. Operator sends the reply to the customer
 */
@RestController
@RequestMapping("/api/containers")
public class ContainerController {

    private final ContainerService containerService;

    public ContainerController(ContainerService containerService) {
        this.containerService = containerService;
    }

    /**
     * Retrieve container data by ISO 6346 number.
     *
     * HTTP method: GET
     * URL: /api/containers/{number}
     *
     * Success response (200 OK):
     * <pre>
     * {
     *   "containerNumber": "MSCU1234567",
     *   "status": "AT_PORT",
     *   "location": "Felixstowe Terminal 2",
     *   "eta": "2026-04-25T14:30:00",
     *   "updatedAt": "2026-04-20T10:15:00"
     * }
     * </pre>
     *
     * Error response (404 Not Found) – container not found in the DB.
     *
     * @param number the container number from the URL path
     * @return ResponseEntity.ok(DTO) if found, ResponseEntity.notFound() otherwise
     */
    @GetMapping("/{number}")
    public ResponseEntity<ContainerResponse> getByNumber(@PathVariable String number) {
        return containerService.findByNumber(number)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
