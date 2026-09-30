package com.smartfreight.inbox.service;

import com.smartfreight.inbox.startup.DemoMailProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit test for {@link GmailDraftService}'s IMAP session — the Drafts APPEND connection logs in
 * with the platform account's password, so it gets the same verified TLS as every other mail path.
 */
class GmailDraftServiceTest {

    @Test
    void imapProperties_verifyTheServerCertificate() {
        GmailDraftService service = new GmailDraftService(mock(SettingsService.class),
                new MailTlsPolicy(new DemoMailProperties()), new SimpleMeterRegistry(),
                "imap.gmail.com", 993, "platform@smartfreight.com", "pw");

        Properties p = service.buildImapProperties();

        assertThat(p.getProperty("mail.imaps.ssl.enable")).isEqualTo("true");
        assertThat(p.getProperty("mail.imaps.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p).doesNotContainKey("mail.imaps.ssl.trust");
    }
}
