package com.smartfreight.inbox.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * Plain data object (POJO) — container information from the external port-service.
 *
 * This is NOT a JPA entity (@Entity) — data is received via HTTP from port-service
 * and used only during AI draft generation ({@link com.smartfreight.inbox.service.AiDraftingService}).
 * It is not persisted to the DB; it lives in memory only for the duration of the request.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Container {

    /** ISO 6346 container number (e.g. MSCU1234567). */
    private String containerNumber;

    /** Current container status (e.g. "In Transit", "At Port"). */
    private String status;

    /** Estimated Time of Arrival (ETA). */
    private LocalDateTime eta;

    /** Current container location (e.g. "Felixstowe Port"). */
    private String location;

    /** Timestamp of the last data update in the port-service system. */
    private LocalDateTime updatedAt;
}
