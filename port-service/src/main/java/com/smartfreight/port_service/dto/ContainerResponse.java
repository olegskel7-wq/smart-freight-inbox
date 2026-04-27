package com.smartfreight.port_service.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * DTO (Data Transfer Object) REST API atsakymui su konteinerio duomenimis.
 *
 * DTO atskirtas nuo JPA esybės dėl kelių priežasčių:
 * 1. Inkapsuliacija – vieši API nematys vidinių laukų (id, versija)
 * 2. API grynumas – DTO apima tik klientams reikalingus laukus
 * 3. Nepriklausomybė – keičiantis DB schemai, API išlieka stabilus
 * 4. Saugumas – jautri DB informacija (pvz. slaptažodžių maišos) nepatenka į atsakymą
 *
 * Naudojama kaip:
 * {@code GET /api/containers/MSCU1234567 → ContainerResponse JSON}
 *
 * @param containerNumber konteinerio numeris (ISO 6346)
 * @param status būsena uosto sistemoje (IN_TRANSIT, AT_PORT, DELIVERED ir kt.)
 * @param location dabartinė vieta (uosto terminalas, muitinė)
 * @param eta numatomas atvykimo / išleidimo laikas klientui
 * @param updatedAt paskutinio būsenos atnaujinimo laikas
 */
public record ContainerResponse(
        String containerNumber,
        String status,
        String location,

        // @JsonFormat – serializuoja LocalDateTime į ISO 8601 formatą.
        // Be šio – JSON būtų pateikiamas kaip objektas su atskirais laukais;
        // su šiuo – kompaktiška eilutė: "2026-04-25T14:30:00".
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime eta,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime updatedAt
) {
    /**
     * Konvertuoja JPA esybę Container į REST DTO.
     *
     * Šablonas: mapiavimo logika saugoma specialiame metode, o ne valdiklyje.
     * Tai supaprastina:
     * – Pakartotinį naudojimą skirtingose vietose (valdiklis, paslauga, testai)
     * – Mapiavimo matomumą (akivaizdu, kaip Entity → DTO)
     * – Testavimą (transformaciją galima testuoti atskirai)
     *
     * @param c JPA konteinerio esybė
     * @return DTO paruoštas JSON serializacijai
     */
    public static ContainerResponse from(com.smartfreight.port_service.entity.Container c) {
        return new ContainerResponse(
                c.getContainerNumber(),
                c.getStatus(),
                c.getLocation(),
                c.getEta(),
                c.getUpdatedAt()
        );
    }
}
