package com.smartfreight.inbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A proactive quote follow-up subscription (Phase 2.5).
 *
 * <p>The product is reactive by default — a customer emails an RFQ, the AI replies with a quote,
 * and there it ends. A follow-up turns that single quote into a one-shot nudge: once an RFQ
 * reply is actually sent, {@code QuoteFollowUpScheduler} waits {@code quotefollowup.delay-hours}
 * and, if the customer has stayed silent, emails one gentle "just following up on our quote"
 * message — then the subscription is done.
 *
 * <p>{@code quoteSentAt} anchors the delay; {@code active} flips to false once the nudge fires OR
 * the customer replies (a new inbound from the same address — sender-based detection in the
 * scheduler). Guarded by both isolation layers: {@code @TenantId} for entity queries and the V24
 * RLS policy for everything else.
 */
@Entity
@Table(name = "quote_follow_up")
@Getter
@Setter
@NoArgsConstructor
public class QuoteFollowUp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant — assigned on insert, filters every entity query. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** The quoted RFQ ticket — provides the original enquiry text for drafting and links back to it. */
    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    /** Where the nudge is sent — the cleaned address the quote was sent to. */
    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    /** When the quote reply went out — the delay window is measured from here. */
    @Column(name = "quote_sent_at", nullable = false)
    private LocalDateTime quoteSentAt;

    /** False once the single nudge has fired or the customer replied — the poller then skips it. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
