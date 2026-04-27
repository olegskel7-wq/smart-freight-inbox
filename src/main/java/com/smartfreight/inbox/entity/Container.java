package com.smartfreight.inbox.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * Paprastas duomenų objektas (POJO) – konteinerio informacija iš išorinio port-service.
 *
 * Tai nėra JPA esybė (@Entity) – duomenys gaunami per HTTP iš port-service
 * ir naudojami tik AI juodraščio generavimo metu ({@link com.smartfreight.inbox.service.AiDraftingService}).
 * DB neišsaugoma, gyvuoja tik užklausos metu atmintyje.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Container {

    /** ISO 6346 konteinerio numeris (pvz.: MSCU1234567). */
    private String containerNumber;

    /** Dabartinė konteinerio būsena (pvz.: "In Transit", "At Port"). */
    private String status;

    /** Numatomas atvykimo laikas (Estimated Time of Arrival). */
    private LocalDateTime eta;

    /** Dabartinė konteinerio buvimo vieta (pvz.: "Felixstowe Port"). */
    private String location;

    /** Duomenų atnaujinimo laikas port-service sistemoje. */
    private LocalDateTime updatedAt;
}
