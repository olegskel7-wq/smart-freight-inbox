package com.smartfreight.inbox.entity;

import com.smartfreight.inbox.entity.enums.TicketType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * A captured operator correction (Phase 3.1 — AI Feedback Loop).
 *
 * <p>When an operator edits an AI draft and sends it, the pair "what the AI wrote
 * ({@link #aiDraft}) → what the operator actually sent ({@link #operatorFinal})" is recorded
 * here against the exact input the AI saw ({@link #enquiryText}). Session 2 retrieves the most
 * similar past corrections for a tenant + ticket type and injects them into the drafting prompt
 * as few-shot examples, so drafts improve as corrections accumulate.
 *
 * <p>Guarded by both isolation layers: {@code @TenantId} for entity queries and the V28 RLS
 * policy for native SQL / raw JDBC — identical to {@link ShipmentWatch}/{@link TenantDocument}.
 *
 * <p>The {@code embedding} column ({@code vector(768)}, nomic-embed-text) is deliberately NOT
 * mapped here: pgvector is a vendor type, so it is written/read via native SQL in
 * {@link com.smartfreight.inbox.repository.TrainingExampleRepository} to keep the entity portable.
 */
@Entity
@Table(name = "training_example")
@Getter
@Setter
@NoArgsConstructor
public class TrainingExample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant — assigned on insert, filters every entity query. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** Source ticket for traceability; no FK so purging the ticket leaves the example intact. */
    @Column(name = "source_ticket_id")
    private Long sourceTicketId;

    /** Ticket type of the source ticket — the pre-filter dimension for retrieval. May be null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_type", length = 50)
    private TicketType ticketType;

    /** The exact input the AI saw: email body + parsed attachment text. */
    @Column(name = "enquiry_text", columnDefinition = "TEXT", nullable = false)
    private String enquiryText;

    /** The original AI-generated draft (before the operator edited it). */
    @Column(name = "ai_draft", columnDefinition = "TEXT", nullable = false)
    private String aiDraft;

    /** What the operator actually sent — the desired output to learn from. */
    @Column(name = "operator_final", columnDefinition = "TEXT", nullable = false)
    private String operatorFinal;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
