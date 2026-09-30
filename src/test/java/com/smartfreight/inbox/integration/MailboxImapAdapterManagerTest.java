package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.service.MailTlsPolicy;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.startup.DemoMailProperties;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.integration.dsl.context.IntegrationFlowContext;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link MailboxImapAdapterManager}: the adapter → ingestion hop (every arriving
 * message is ingested inside the mailbox's tenant AND attributed to the mailbox, so the reply
 * later leaves through the same inbox — V37), and the adapter's TLS config (a real server's
 * certificate is verified; only a demo GreenMail mailbox is trusted by host).
 */
class MailboxImapAdapterManagerTest {

    private EmailIngestionService ingestion;
    private DemoMailProperties demo;
    private MailboxImapAdapterManager manager;

    @BeforeEach
    void setUp() {
        ingestion = mock(EmailIngestionService.class);
        demo = new DemoMailProperties();
        manager = new MailboxImapAdapterManager(mock(IntegrationFlowContext.class), ingestion,
                mock(SecretCipher.class), new MailTlsPolicy(demo), new SimpleMeterRegistry());
    }

    @Test
    void ingest_bindsTheMailboxTenant_andPassesTheMailboxId() {
        MimeMessage message = mock(MimeMessage.class);
        AtomicReference<Long> tenantDuringIngestion = new AtomicReference<>();
        doAnswer(inv -> {
            tenantDuringIngestion.set(TenantContext.tenantIdOrNull());
            return null;
        }).when(ingestion).processIncomingEmail(any(), eq(7L));

        manager.ingest(90001L, 7L, message);

        assertThat(tenantDuringIngestion.get()).isEqualTo(90001L);
    }

    @Test
    void javaMailProperties_realMailbox_verifiesTheServerCertificate() {
        Properties p = manager.javaMailProperties(mailbox("imap.gmail.com"), "pw");

        assertThat(p.getProperty("mail.imaps.ssl.enable")).isEqualTo("true");
        assertThat(p.getProperty("mail.imaps.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p).doesNotContainKey("mail.imaps.ssl.trust");
    }

    @Test
    void javaMailProperties_demoGreenMailMailbox_trustsItsSelfSignedCert() {
        demo.setEnabled(true);

        Properties p = manager.javaMailProperties(mailbox("greenmail"), "demo");

        assertThat(p.getProperty("mail.imaps.ssl.trust")).isEqualTo("greenmail");
    }

    private static TenantMailbox mailbox(String host) {
        TenantMailbox m = new TenantMailbox();
        m.setHost(host);
        m.setUsername("inbox@acme.co.uk");
        return m;
    }
}
