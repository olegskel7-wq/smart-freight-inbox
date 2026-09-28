package com.smartfreight.inbox.entity;

import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.entity.enums.Urgency;
import com.smartfreight.inbox.messaging.TicketLiveEventListener;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * JPA entity mapped to the {@code email_ticket} table in the database.
 *
 * One ticket = one received email with the full processing context:
 * sender, subject, body, extracted container, AI draft, status.
 *
 * Soft delete ({@link SQLDelete}): instead of DELETE, an UPDATE SET deleted=true is executed —
 * records remain in the DB for GDPR purposes but are invisible to all SELECT queries ({@link SQLRestriction}).
 *
 * Optimistic lock ({@link Version}): protects against race conditions
 * when two threads concurrently try to update the same ticket.
 */
@Entity
@Table(
    name = "email_ticket",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "message_id"})
)
// Phase 3.2: broadcast a real-time event to the tenant's STOMP topic on every write.
@EntityListeners(TicketLiveEventListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder

@SQLDelete(sql = "UPDATE email_ticket SET deleted = true WHERE id=? AND version=?")
@SQLRestriction("deleted=false")
public class EmailTicket {

    /** Primary key. IDENTITY = PostgreSQL generates the next ID automatically. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning tenant (Phase 2.2). Assigned by Hibernate on insert from the current
     * {@link com.smartfreight.inbox.tenancy.TenantContext}; every entity query is
     * automatically restricted to it. Postgres RLS enforces the same boundary
     * independently for native SQL.
     */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /**
     * Unique message identifier from the RFC 2822 "Message-ID" header.
     * Used for duplicate detection — the same message is never saved twice
     * per tenant (two tenants may legitimately receive a copy of one email).
     */
    @Column(name = "message_id", nullable = false)
    private String messageId;

    /** Email sender address (e.g. customer@company.com). */
    @Column(name = "sender_email")
    private String senderEmail;

    /** Email subject (Subject header). */
    @Column(name = "subject")
    private String subject;

    /** Full email body. TEXT SQL type — unlimited length (unlike VARCHAR). */
    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /**
     * Text extracted from PDF/Excel attachments (Phase 1.1), if any.
     * Stored separately from {@link #body} so the operator UI can distinguish email text from
     * attachment content; the AI pipeline combines both when building extraction/drafting prompts.
     * Null when the email had no parseable attachments.
     */
    @Column(name = "attachment_text", columnDefinition = "TEXT")
    private String attachmentText;

    /** Key identifier extracted by the AI from the email body (e.g. MSCU1234567). */
    @Column(name = "extracted_identifier")
    private String extractedIdentifier;

    /**
     * Ticket lifecycle status.
     * EnumType.STRING stores the enum as text "NEW", not a number —
     * this is important: adding a new status does not corrupt existing records.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private TicketStatus status = TicketStatus.NEW;

    /**
     * Business type of the email (Phase 1.4), assigned by the AI classifier together with
     * the relevance decision. Null for pre-1.4 tickets, retry-path re-entries (classification
     * is not repeated) and IRRELEVANT tickets.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_type")
    private TicketType ticketType;

    /**
     * How time-critical the email is (Phase 3.10), assigned by the AI classifier in the same Qwen call
     * as relevance + type. Stored ORDINAL (LOW=0/NORMAL=1/HIGH=2) so the operator queue can order by
     * {@code urgency DESC} as a plain column — see {@link Urgency}. Drives a tighter SLA window for HIGH
     * and escalated-first list ordering; it never changes the AI draft. Defaults to NORMAL (also the
     * fail-open verdict), so legacy rows and classifier outages read as normal priority.
     */
    @Enumerated(EnumType.ORDINAL)
    @JdbcTypeCode(SqlTypes.SMALLINT)   // pin to SMALLINT (int2) — matches V34; avoids Hibernate's TINYINT-default vs Postgres int2 validate clash
    @Column(name = "urgency", nullable = false)
    @Builder.Default
    private Urgency urgency = Urgency.NORMAL;

    /**
     * True when the classifier judged the customer's tone negative/angry (Phase 3.10). Like a HIGH
     * urgency it escalates the ticket (tighter SLA + top of the queue) and shows a badge, so an unhappy
     * customer is seen first. Defaults false (calm) — the fail-open and legacy value.
     */
    @Column(name = "sentiment_negative", nullable = false)
    @Builder.Default
    private boolean sentimentNegative = false;

    /**
     * Reply-by deadline (Phase 1.5) = createdAt + per-type SLA window. Set at ingest with
     * the GENERAL window, refined once the classifier assigns the type, cleared for
     * IRRELEVANT. Null for pre-1.5 tickets and when feature.sla-timer is off.
     */
    @Column(name = "sla_deadline_at")
    private LocalDateTime slaDeadlineAt;

    /**
     * True once the deadline passed while the ticket was still open (flipped by
     * SlaBreachScheduler). Deliberately never reset — a late reply stays a breach
     * in the analytics record.
     */
    @Column(name = "sla_breached", nullable = false)
    @Builder.Default
    private boolean slaBreached = false;

    /** Reply draft generated by AI (Llama 3.1). The operator reviews and sends it. */
    @Column(name = "draft_reply", columnDefinition = "TEXT")
    private String draftReply;

    /**
     * The unmodified AI-generated draft (Phase 3.1), captured by the pipeline alongside
     * {@link #draftReply}. Unlike {@code draftReply} — which the operator's edits overwrite — this
     * is never touched after generation, so the feedback loop can compare it against what the
     * operator actually sent and learn from the difference. Null for pre-3.1 and proactive tickets.
     */
    @Column(name = "ai_original_draft", columnDefinition = "TEXT")
    private String aiOriginalDraft;

    /**
     * For DOCUMENT_REQUEST tickets (Phase 2.7): the document type the AI determined the customer
     * is asking for. Shown in the operator UI for every such ticket — even when no matching file
     * is on hand — so the operator knows what to provide. Null for non-document tickets.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "requested_doc_type", length = 50)
    private DocumentType requestedDocType;

    /**
     * The {@link TenantDocument} resolved for this reply (Phase 2.7): set by the pipeline when a
     * matching document is on file, or by the operator from the ticket page. {@code EmailReplyService}
     * loads it and attaches the bytes on send (both manual and auto-send). Null = text-only reply.
     */
    @Column(name = "attached_document_id")
    private Long attachedDocumentId;

    /**
     * True for outbound proactive status updates (Phase 2.4) — tickets the platform creates
     * itself when a watched shipment hits a milestone, not mail a customer sent in. They reuse
     * the ticket model for visibility/audit/auto-send, but are NOT inbound: analytics "received"
     * counts exclude them and {@code EmailReplyService} sends them with a fresh subject (no "Re:").
     */
    @Column(name = "proactive", nullable = false)
    @Builder.Default
    private boolean proactive = false;

    /**
     * The {@link TenantMailbox} this enquiry arrived on (V37) — a tenant may watch several
     * inboxes, and {@code MailSenderProvider} replies through this one so the customer hears
     * back from the address they wrote to. Proactive tickets inherit it from the ticket they
     * derive from. Null for pre-V37/seeded tickets or once the mailbox is deleted (the DB
     * clears it) — the sender then falls back to the tenant default. Write-once
     * ({@code updatable = false}): a stale entity must never re-point it at a deleted mailbox.
     */
    @Column(name = "mailbox_id", updatable = false)
    private Long mailboxId;

    /**
     * Heuristic confidence score 0.0–1.0 (Phase 1.6), computed from verifiable pipeline facts
     * (type classified, identifier extracted, item found, rate matched) — deliberately NOT an
     * LLM self-assessment, which small models cannot calibrate. Written on every drafting run
     * even when feature.auto-send is off, so the score distribution accumulates for threshold
     * tuning. Null for pre-1.6 tickets and tickets that never reached drafting.
     */
    @Column(name = "ai_confidence")
    private Double aiConfidence;

    /**
     * Owning operator for the shared queue (Phase 3.3 S2) — a Keycloak user UUID, or null when the
     * ticket is unclaimed. An operator self-claims; a tenant admin can assign to a teammate. Plain
     * column (not an FK): operators live in Keycloak (Phase 2.3), not a local users table. Mutating
     * it fires the existing {@code @PostUpdate} live event, so the list updates without extra wiring.
     */
    @Column(name = "assignee_user_id", length = 36)
    private String assigneeUserId;

    /**
     * Retry attempt counter.
     * When AI is temporarily unavailable the ticket receives RETRY_LATER status
     * and this counter is incremented for each attempt.
     */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * Soft-delete flag. false = record active, true = "deleted" (GDPR).
     * SQLRestriction automatically filters out deleted=true records.
     */
    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    /** Ticket creation timestamp. updatable=false — never changed after the initial save. */
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Last modification timestamp — updated on every save(). */
    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Optimistic lock version.
     * Hibernate automatically increments this number on every UPDATE.
     * If two threads both read version=5 and both try to save,
     * the second will receive ObjectOptimisticLockingFailureException.
     */
    @Version
    private Integer version;
}
