package com.smartfreight.port_service.controller;

import com.smartfreight.port_service.dto.ContainerResponse;
import com.smartfreight.port_service.service.ContainerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST valdiklis konteinerių duomenų užklausoms.
 *
 * Mikropaslauga port-service teikia vieną skaitymo galinį tašką:
 * {@code GET /api/containers/{containerNumber}}
 *
 * Pagrindinis inbox programos ContainerLookupService naudoja šį galinį tašką
 * konteinerio būsenai gauti apdorojant klientų el. pašto užklausas.
 *
 * Duomenų srautas:
 * 1. Klientas siunčia el. laišką: „Kur mano konteineris MSCU1234567?"
 * 2. inbox → AiExtractionService ištraukia numerį MSCU1234567
 * 3. inbox → ContainerLookupService siunčia: GET /api/containers/MSCU1234567
 * 4. port-service grąžina JSON su dabartine būsena
 * 5. inbox → AiDraftingService generuoja atsakymą pagal gautus duomenis
 * 6. Operatorius išsiunčia atsakymą klientui
 */
@RestController
@RequestMapping("/api/containers")
public class ContainerController {

    private final ContainerService containerService;

    public ContainerController(ContainerService containerService) {
        this.containerService = containerService;
    }

    /**
     * Gauti konteinerio duomenis pagal ISO 6346 numerį.
     *
     * HTTP metodas: GET
     * URL: /api/containers/{number}
     *
     * Sėkmingas atsakymas (200 OK):
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
     * Klaidos atsakymas (404 Not Found) – konteineris nerastas DB.
     *
     * @param number konteinerio numeris iš URL kelio
     * @return ResponseEntity.ok(DTO) jei rastas, ResponseEntity.notFound() jei ne
     */
    @GetMapping("/{number}")
    public ResponseEntity<ContainerResponse> getByNumber(@PathVariable String number) {
        return containerService.findByNumber(number)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
