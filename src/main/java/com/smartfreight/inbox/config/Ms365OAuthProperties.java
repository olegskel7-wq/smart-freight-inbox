package com.smartfreight.inbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The platform's Microsoft Entra app for Microsoft 365 mailboxes (V38, XOAUTH2 over IMAP/SMTP).
 *
 * <p>Bound from {@code ms365.oauth.*}. ONE multi-tenant app registration serves every customer:
 * each customer's admin grants it consent in their own directory and gives its service principal
 * access to the one mailbox we watch. Per mailbox we therefore store only which directory to ask
 * ({@code tenant_mailbox.ms_directory_id}); the app's own credentials live here, in env.
 *
 * @param clientId     Application (client) ID of our Entra app; blank = M365 sign-in disabled
 * @param clientSecret its client secret (Entra → Certificates &amp; secrets)
 * @param authorityUrl identity platform base URL, no tenant path ({@code https://login.microsoftonline.com})
 * @param scope        resource scope for IMAP/SMTP ({@code https://outlook.office365.com/.default})
 */
@ConfigurationProperties(prefix = "ms365.oauth")
public record Ms365OAuthProperties(
        String clientId,
        String clientSecret,
        String authorityUrl,
        String scope) {

    /** Whether the app credentials are present — without them no M365 mailbox can sign in. */
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }
}
