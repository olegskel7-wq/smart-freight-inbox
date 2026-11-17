package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.service.MailTlsPolicy;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.startup.DemoMailProperties;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.integration.dsl.context.IntegrationFlowContext;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void ingest_failure_neverEscapesIntoTheIdleAdapter() {
        // Gate B5: a non-MessagingException escaping the handler ends the IDLE loop for good.
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Connection is not available"))
                .when(ingestion).processIncomingEmail(any(), eq(7L));

        boolean processed = manager.ingest(90001L, 7L, mock(MimeMessage.class));

        assertThat(processed).isFalse();
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

    @Test
    void receivedSinceConnection_skipsMailFromBeforeTheMailboxWasConnected() throws Exception {
        // Gate B6: on first connect IDLE must not turn the client's old unread INBOX into tickets.
        Instant connected = Instant.parse("2026-10-29T10:00:00Z");

        assertThat(MailboxImapAdapterManager.receivedSinceConnection(connected,
                received(connected.minus(Duration.ofDays(400))))).isFalse();
        assertThat(MailboxImapAdapterManager.receivedSinceConnection(connected,
                received(connected.minusSeconds(1)))).isFalse();
        assertThat(MailboxImapAdapterManager.receivedSinceConnection(connected,
                received(connected))).isTrue();
        assertThat(MailboxImapAdapterManager.receivedSinceConnection(connected,
                received(connected.plus(Duration.ofMinutes(5))))).isTrue();
    }

    @Test
    void receivedSinceConnection_failsOpen() throws Exception {
        // Dropping a client's email silently is worse than one stray ticket.
        Instant connected = Instant.parse("2026-10-29T10:00:00Z");
        MimeMessage noDate = mock(MimeMessage.class);
        MimeMessage unreadable = mock(MimeMessage.class);
        when(unreadable.getReceivedDate()).thenThrow(new MessagingException("connection reset"));

        assertThat(MailboxImapAdapterManager.receivedSinceConnection(connected, noDate)).isTrue();
        assertThat(MailboxImapAdapterManager.receivedSinceConnection(connected, unreadable)).isTrue();
        assertThat(MailboxImapAdapterManager.receivedSinceConnection(null,
                received(connected.minus(Duration.ofDays(400))))).isTrue();
    }

    @Test
    void connectedAt_isTheRowCreationInstant() {
        TenantMailbox m = mailbox("imap.gmail.com");
        m.setCreatedAt(null);
        assertThat(MailboxImapAdapterManager.connectedAt(m)).isNull();

        LocalDateTime created = LocalDateTime.of(2026, 10, 29, 10, 0);
        m.setCreatedAt(created);

        // Minus the clock-skew grace: a mail server a few minutes behind must not lose fresh mail.
        assertThat(MailboxImapAdapterManager.connectedAt(m))
                .isEqualTo(created.atZone(ZoneId.systemDefault()).toInstant().minus(Duration.ofMinutes(15)));
    }

    private static MimeMessage received(Instant at) throws MessagingException {
        MimeMessage m = mock(MimeMessage.class);
        when(m.getReceivedDate()).thenReturn(Date.from(at));
        return m;
    }

    private static TenantMailbox mailbox(String host) {
        TenantMailbox m = new TenantMailbox();
        m.setHost(host);
        m.setUsername("inbox@acme.co.uk");
        return m;
    }
}
