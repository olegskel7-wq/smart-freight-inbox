package com.smartfreight.port_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

import static jakarta.persistence.EnumType.STRING;

/**
 * JPA entity representing a container record in the database.
 *
 * Represents a cargo container with current status, location, and ETA information.
 * This data is used by the main inbox application to generate replies
 * to customer email inquiries about cargo status.
 *
 * Example data:
 * – containerNumber = "MSCU1234567" (ISO 6346 format: 4 letters + 7 digits)
 * – status = AT_PORT (possible values: IN_TRANSIT, AT_PORT, CUSTOMS, RELEASED, DELIVERED)
 * – location = "Felixstowe Terminal 2" (port terminal location)
 * – eta = 2026-04-25T14:30:00 (estimated arrival / release time)
 */
@Entity
@Table(name = "container")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Container {

    /** Primary key – unique ID in the database. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Container number according to the ISO 6346 standard.
     *
     * Format: XXXX1234567 (4 letters + 7 digits).
     * Examples: MSCU1234567, OOLU9876543, HLXU5555555.
     *
     * unique = true – the DB does not allow two containers with the same number.
     */
    @Column(name = "container_number", nullable = false, unique = true)
    private String containerNumber;

    /**
     * Current status of the container in the port system.
     *
     * Stored in the DB as VARCHAR (EnumType.STRING) so that values are
     * human-readable and independent of the enum declaration order.
     *
     * {@link ContainerStatus} describes the allowed values and the lifecycle.
     * {@link com.smartfreight.port_service.scheduler.StatusUpdateScheduler}
     * periodically advances statuses (simulation).
     */
    @Enumerated(STRING)
    @Column(name = "status", length = 50)
    private ContainerStatus status;

    /**
     * Estimated time of arrival at the port or release to the customer (ETA).
     * Used in replies such as: "Your container is expected to arrive on April 25 at 14:30".
     */
    @Column(name = "eta")
    private LocalDateTime eta;

    /**
     * Current geographical location of the container.
     *
     * Examples:
     * – "Felixstowe Terminal 2" (port terminal)
     * – "Customs Warehouse A" (customs warehouse)
     * – "Transit to client" (in transit to the customer)
     */
    @Column(name = "location")
    private String location;

    /**
     * Timestamp of the last update to the container information.
     * Used for debugging and auditing.
     * {@link com.smartfreight.port_service.scheduler.StatusUpdateScheduler}
     * automatically updates this field when the status changes.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
