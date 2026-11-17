package com.smartfreight.inbox.entity;

/**
 * How the platform signs in to a {@link TenantMailbox} (V38).
 *
 * <p>{@code PASSWORD} — the account password / app password, AES-GCM in {@code password_enc}
 * (Gmail, generic IMAP hosts). {@code MS365_OAUTH} — XOAUTH2 with an app-only token our Entra app
 * obtains from the customer's directory ({@code ms_directory_id}); no password is stored.
 */
public enum MailboxAuthType {
    PASSWORD,
    MS365_OAUTH
}
