package com.smartfreight.inbox.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Plain data object (POJO) — a tracked item looked up in an external backend.
 *
 * Vertical-neutral model used by the AI pipeline: the {@code identifier} is whatever key the
 * vertical tracks (a freight container number, a property reference, a case number, ...).
 *
 * This is NOT a JPA entity (@Entity) — data is received via HTTP from the backend
 * (see {@link com.smartfreight.inbox.service.ItemLookupService}) and used only during AI draft
 * generation ({@link com.smartfreight.inbox.service.AiDraftingService}). It is not persisted to the
 * DB; it lives in memory only for the duration of the request.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrackedItem {

    /**
     * Key identifier of the tracked item (e.g. an ISO 6346 container number).
     *
     * Mapped from the freight backend's {@code containerNumber} JSON field so the wire contract
     * with port-service stays unchanged while the model itself is vertical-neutral.
     */
    @JsonProperty("containerNumber")
    private String identifier;

    /** Current status (e.g. "In Transit", "At Port"). Free-form — defined by the backend. */
    private String status;

    /** Estimated Time of Arrival (ETA). */
    private LocalDateTime eta;

    /** Current location (e.g. "Felixstowe Port"). */
    private String location;

    /** Timestamp of the last data update in the backend system. */
    private LocalDateTime updatedAt;
}
