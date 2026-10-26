package com.smartfreight.inbox.entity;

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

import java.time.LocalDateTime;

/**
 * One IMAP inbox watched for a tenant (Phase 2.2 S3).
 *
 * <p>Every enabled row gets its own IMAP IDLE adapter, registered at runtime by the
 * {@code MailboxReconciler}; mail arriving on it is ingested inside the owning tenant's
 * {@code TenantContext}. Several rows per tenant are allowed (Phase 3.3 multi-inbox),
 * the same (host, username) per tenant is not — V21 unique constraint, because a second
 * adapter on the same account would double-ingest every message.
 *
 * <p>{@code passwordEnc} is AES-256-GCM ciphertext produced by
 * {@link com.smartfreight.inbox.service.SecretCipher} — the plaintext IMAP password
 * never touches the DB or its backups. Guarded by both isolation layers:
 * {@code @TenantId} for entity queries and the V21 RLS policy for everything else.
 */
@Entity
@Table(name = "tenant_mailbox")
@Getter
@Setter
@NoArgsConstructor
public class TenantMailbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant — assigned on insert, filters every entity query. */
    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "host", nullable = false)
    private String host;

    @Column(name = "port", nullable = false)
    private int port = 993;

    @Column(name = "username", nullable = false)
    private String username;

    /** How the platform signs in — password (default, every pre-V38 row) or Microsoft 365 OAuth2. */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false)
    private MailboxAuthType authType = MailboxAuthType.PASSWORD;

    /**
     * AES-GCM ciphertext (base64) — decrypt via {@code SecretCipher} only at adapter build time.
     * Null for {@link MailboxAuthType#MS365_OAUTH} rows (V38 CHECK enforces it per auth type).
     */
    @Column(name = "password_enc")
    private String passwordEnc;

    /**
     * The customer's Microsoft Entra directory (tenant GUID or verified domain) our app requests
     * tokens from. Set only for {@link MailboxAuthType#MS365_OAUTH}; not a secret.
     */
    @Column(name = "ms_directory_id")
    private String msDirectoryId;

    /**
     * Outbound SMTP host for this account (e.g. {@code smtp.gmail.com}). Null = inbound-only
     * mailbox: replies for this tenant fall back to the platform SMTP account. The SMTP
     * password reuses {@link #passwordEnc} — one account, one credential (Gmail/O365 app
     * passwords work this way).
     */
    @Column(name = "smtp_host")
    private String smtpHost;

    /** SMTP port — 587 (STARTTLS) or 465 (SMTPS). Null until smtpHost is set. */
    @Column(name = "smtp_port")
    private Integer smtpPort;

    /** Address replies are sent FROM. Null = use {@link #username}. */
    @Column(name = "from_address")
    private String fromAddress;

    /** Disabled rows keep their credentials but get no adapter — pause without deleting. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** The address mail from this account goes out FROM: {@link #fromAddress}, else {@link #username}. */
    public String effectiveFromAddress() {
        return fromAddress != null && !fromAddress.isBlank() ? fromAddress : username;
    }
}
