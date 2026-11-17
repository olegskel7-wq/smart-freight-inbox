package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.MailboxAuthType;
import com.smartfreight.inbox.entity.TenantMailbox;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MailboxCredentialsTest {

    private final SecretCipher cipher = mock(SecretCipher.class);
    private final Ms365TokenProvider tokens = mock(Ms365TokenProvider.class);
    private final MailboxCredentials credentials = new MailboxCredentials(cipher, tokens);

    private static TenantMailbox passwordMailbox() {
        TenantMailbox m = new TenantMailbox();
        m.setPasswordEnc("cipher-text");
        return m;
    }

    private static TenantMailbox oauthMailbox() {
        TenantMailbox m = new TenantMailbox();
        m.setAuthType(MailboxAuthType.MS365_OAUTH);
        m.setMsDirectoryId("contoso.onmicrosoft.com");
        return m;
    }

    @Test
    void passwordMailbox_decryptsAndKeepsPlainAuth() {
        when(cipher.decrypt("cipher-text")).thenReturn("pw");
        Properties props = new Properties();

        assertThat(credentials.secret(passwordMailbox())).isEqualTo("pw");
        credentials.applyImaps(props, passwordMailbox());
        credentials.applySmtp(props, passwordMailbox());

        assertThat(props).isEmpty();
        verifyNoInteractions(tokens);
    }

    @Test
    void oauthMailbox_presentsAFreshTokenThroughXoauth2() {
        when(tokens.accessToken("contoso.onmicrosoft.com")).thenReturn("tok-1");
        Properties props = new Properties();

        assertThat(credentials.secret(oauthMailbox())).isEqualTo("tok-1");
        credentials.applyImaps(props, oauthMailbox());
        credentials.applySmtp(props, oauthMailbox());

        assertThat(props.getProperty("mail.imaps.auth.mechanisms")).isEqualTo("XOAUTH2");
        assertThat(props.getProperty("mail.smtp.auth.mechanisms")).isEqualTo("XOAUTH2");
        verifyNoInteractions(cipher);
    }

    @Test
    void canSignIn_checksTheConfigTheAuthTypeNeeds() {
        when(cipher.isConfigured()).thenReturn(true);
        when(tokens.isConfigured()).thenReturn(false);

        assertThat(credentials.canSignIn(passwordMailbox())).isTrue();
        assertThat(credentials.canSignIn(oauthMailbox())).isFalse();
        assertThat(credentials.missingConfig(oauthMailbox())).contains("MS365_CLIENT_ID");
    }

    @Test
    void fingerprint_isNullSafe_tracksTheDirectory_andNeverHoldsTheSecret() {
        TenantMailbox oauth = oauthMailbox();
        String before = MailboxCredentials.fingerprint(oauth);
        oauth.setMsDirectoryId("fabrikam.onmicrosoft.com");

        assertThat(MailboxCredentials.fingerprint(oauth)).isNotEqualTo(before).startsWith("MS365_OAUTH:");
        assertThat(MailboxCredentials.fingerprint(passwordMailbox()))
                .startsWith("PASSWORD:").doesNotContain("cipher-text");
    }
}
