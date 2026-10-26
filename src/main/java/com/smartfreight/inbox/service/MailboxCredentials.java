package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.MailboxAuthType;
import com.smartfreight.inbox.entity.TenantMailbox;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Properties;

/**
 * The single owner of "how do we sign in to this mailbox" (V38) — every IMAP/SMTP login site asks
 * here instead of decrypting {@code password_enc} itself.
 *
 * <p>{@link MailboxAuthType#PASSWORD}: the decrypted account password, plain AUTH as before.
 * {@link MailboxAuthType#MS365_OAUTH}: an app-only access token from {@link Ms365TokenProvider},
 * presented through SASL XOAUTH2 — JavaMail sends the token in the password slot once the
 * {@code auth.mechanisms} property says so. The token expires within the hour, so callers fetch
 * {@link #secret} at every connect and never keep it.
 */
@Component
public class MailboxCredentials {

    private final SecretCipher secretCipher;
    private final Ms365TokenProvider tokenProvider;

    public MailboxCredentials(SecretCipher secretCipher, Ms365TokenProvider tokenProvider) {
        this.secretCipher = secretCipher;
        this.tokenProvider = tokenProvider;
    }

    /** Whether the platform holds what this mailbox's sign-in needs (encryption key / Entra app). */
    public boolean canSignIn(TenantMailbox mailbox) {
        return isOAuth(mailbox) ? tokenProvider.isConfigured() : secretCipher.isConfigured();
    }

    /** Why {@link #canSignIn} is false, for the log line — names the missing env, never a value. */
    public String missingConfig(TenantMailbox mailbox) {
        return isOAuth(mailbox) ? "MS365_CLIENT_ID / MS365_CLIENT_SECRET are not set"
                : "MAILBOX_ENCRYPTION_KEY is not set";
    }

    /** What goes in the password slot of a connect: the password, or a current access token. */
    public String secret(TenantMailbox mailbox) {
        return isOAuth(mailbox)
                ? tokenProvider.accessToken(mailbox.getMsDirectoryId())
                : secretCipher.decrypt(mailbox.getPasswordEnc());
    }

    /** XOAUTH2 for an {@code imaps} store when the mailbox signs in with OAuth; no-op otherwise. */
    public void applyImaps(Properties props, TenantMailbox mailbox) {
        if (isOAuth(mailbox)) {
            props.put("mail.imaps.auth.mechanisms", "XOAUTH2");
        }
    }

    /** XOAUTH2 for an {@code smtp} transport when the mailbox signs in with OAuth; no-op otherwise. */
    public void applySmtp(Properties props, TenantMailbox mailbox) {
        if (isOAuth(mailbox)) {
            props.put("mail.smtp.auth.mechanisms", "XOAUTH2");
        }
    }

    /**
     * The credential part of a config fingerprint: changes when the stored password or the
     * directory changes, so adapters/senders rebuild — never contains the secret itself.
     */
    public static String fingerprint(TenantMailbox mailbox) {
        return mailbox.getAuthType() + ":" + (isOAuth(mailbox)
                ? Objects.toString(mailbox.getMsDirectoryId())
                : Integer.toString(Objects.hashCode(mailbox.getPasswordEnc())));
    }

    private static boolean isOAuth(TenantMailbox mailbox) {
        return mailbox.getAuthType() == MailboxAuthType.MS365_OAUTH;
    }
}
