package com.smartfreight.port_service.service;

import com.smartfreight.port_service.dto.ContainerResponse;
import com.smartfreight.port_service.repository.ContainerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Verslo logika darbui su konteineriais.
 *
 * Paslauga atskirta nuo valdiklio pagal vienos atsakomybės principą (SRP):
 * – Valdiklis tvarko HTTP užklausas (maršrutas, serializacija)
 * – Paslauga apima verslo logiką (normalizavimas, transformavimas, validacija)
 *
 * Tai leidžia verslo logiką pakartotinai naudoti:
 * – REST API per ContainerController
 * – Integracijoje su kitomis mikropaslaugomis
 * – Paketiniam apdorojimui (jei prireiktų)
 */
@Service
public class ContainerService {

    private static final Logger log = LoggerFactory.getLogger(ContainerService.class);

    private final ContainerRepository containerRepository;

    public ContainerService(ContainerRepository containerRepository) {
        this.containerRepository = containerRepository;
    }

    /**
     * Rasti konteinerį pagal numerį ir konvertuoti į DTO.
     *
     * Procesas:
     * 1. Įvesties validacija (null, tuščia eilutė)
     * 2. Normalizavimas: trim() ir toUpperCase() – ISO numeriai standartizuoti
     * 3. Paieška DB per saugyklą
     * 4. Jei rastas – Entity transformacija į DTO per ContainerResponse.from()
     * 5. Veiksmų žurnalavimas (auditui ir derinimui)
     *
     * Naudojamas inbox programos per HTTP:
     * {@code inbox → ContainerLookupService → WebClient.get(/api/containers/{number}) → port-service}
     *
     * @param containerNumber konteinerio numeris (gali būti null, tuščias, skirtingo registro)
     * @return Optional.of(DTO) jei konteineris rastas, Optional.empty() jei ne
     */
    public Optional<ContainerResponse> findByNumber(String containerNumber) {
        if (containerNumber == null || containerNumber.isBlank()) {
            log.warn("Container lookup called with blank number");
            return Optional.empty();
        }

        // Normalizavimas: "mscu1234567" → "MSCU1234567"
        // Būtina, nes konteinerių numeriai dažnai rašomi skirtingais registrais
        String normalized = containerNumber.trim().toUpperCase();

        return containerRepository.findByContainerNumber(normalized)
                .map(c -> {
                    log.info("Container found: {} | status={} | location={}",
                            c.getContainerNumber(), c.getStatus(), c.getLocation());
                    return ContainerResponse.from(c);
                });
    }
}
