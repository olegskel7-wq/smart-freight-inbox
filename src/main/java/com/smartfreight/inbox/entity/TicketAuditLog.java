package com.smartfreight.inbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Immutable audit record of an operator action on a ticket.
 *
 * Append-only: never updated or deleted (no {@code @Version}, no soft-delete).
 * Intended for compliance (GDPR Article 30 "record of processing") and incident
 * response — answers "who pressed Send on this ticket and when".
 *
 * Action codes (uppercase string, free-form for forward compatibility):
 * <ul>
 *   <li>{@code SEND}   — operator dispatched the reply via SMTP</li>
 *   <li>{@code CLOSE}  — operator closed the ticket without sending</li>
 *   <li>{@code DELETE} — operator soft-deleted the ticket</li>
 *   <li>{@code DRAFT_EDIT} — operator saved an edited draft</li>
 * </ul>
 */
@Entity
@Table(name = "ticket_audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant (Phase 2.2) — assigned on insert, filters every entity query. */
    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** Foreign-key-like reference to {@link EmailTicket#getId()} — not a JPA relation to keep the entity cheap. */
    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    /** Email address from the security context at the time of the action. */
    @Column(name = "actor_email", nullable = false)
    private String actorEmail;

    /** Action code (see class Javadoc). */
    @Column(name = "action", nullable = false, length = 50)
    private String action;

    /** Free-form context — recipient address for SEND, error message on failure, etc. */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
