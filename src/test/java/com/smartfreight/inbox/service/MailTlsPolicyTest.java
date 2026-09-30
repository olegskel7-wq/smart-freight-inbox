package com.smartfreight.inbox.service;

import com.smartfreight.inbox.startup.DemoMailProperties;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MailTlsPolicy} — real mail servers are verified (chain + hostname, no
 * blanket {@code ssl.trust}, STARTTLS required on submission ports); only the demo harness's own
 * hosts are relaxed, and only while the harness is on.
 */
class MailTlsPolicyTest {

    private DemoMailProperties demo;
    private MailTlsPolicy policy;

    @BeforeEach
    void setUp() {
        demo = new DemoMailProperties();   // greenmail / mailpit, harness off
        policy = new MailTlsPolicy(demo);
    }

    @Test
    void imaps_realHost_verifiesCertificateAndHostname() {
        Properties p = new Properties();

        policy.applyImaps(p, "imap.gmail.com");

        assertThat(p.getProperty("mail.imaps.ssl.checkserveridentity")).isEqualTo("true");
        // ssl.trust would accept ANY certificate imap.gmail.com's address presents (MITM).
        assertThat(p).doesNotContainKey("mail.imaps.ssl.trust");
    }

    @Test
    void imaps_demoHostWhileHarnessOn_trustsGreenMailsSelfSignedCert() {
        demo.setEnabled(true);
        Properties p = new Properties();

        policy.applyImaps(p, "greenmail");

        assertThat(p.getProperty("mail.imaps.ssl.trust")).isEqualTo("greenmail");
        // The minted cert names the host, so the hostname check still runs.
        assertThat(p.getProperty("mail.imaps.ssl.checkserveridentity")).isEqualTo("true");
    }

    @Test
    void imaps_demoHostWhileHarnessOff_isVerifiedLikeAnyOther() {
        Properties p = new Properties();

        policy.applyImaps(p, "greenmail");

        assertThat(p).doesNotContainKey("mail.imaps.ssl.trust");
    }

    @Test
    void imaps_realHostWhileHarnessOn_isStillVerified() {
        demo.setEnabled(true);
        Properties p = new Properties();

        policy.applyImaps(p, "imap.gmail.com");

        assertThat(p).doesNotContainKey("mail.imaps.ssl.trust");
    }

    @Test
    void smtp_submissionPort_requiresStartTls() {
        Properties p = new Properties();

        policy.applySmtp(p, "smtp.gmail.com", 587);

        assertThat(p.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p).doesNotContainKey("mail.smtp.ssl.trust");
        assertThat(p).doesNotContainKey("mail.smtp.ssl.enable");
    }

    @Test
    void smtp_port465_isImplicitTls() {
        Properties p = new Properties();

        policy.applySmtp(p, "smtp.gmail.com", 465);

        assertThat(p.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p).doesNotContainKey("mail.smtp.ssl.trust");
    }

    @Test
    void smtp_demoHostWhileHarnessOn_worksWithoutTls() {
        demo.setEnabled(true);
        Properties p = new Properties();

        policy.applySmtp(p, "mailpit", 1025);

        // Mailpit has no TLS: STARTTLS only if offered, never required.
        assertThat(p).doesNotContainKey("mail.smtp.starttls.required");
        assertThat(p.getProperty("mail.smtp.ssl.trust")).isEqualTo("mailpit");
    }

    @Test
    void smtp_demoHostWhileHarnessOff_requiresStartTls() {
        Properties p = new Properties();

        policy.applySmtp(p, "mailpit", 1025);

        assertThat(p.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(p).doesNotContainKey("mail.smtp.ssl.trust");
    }

    @Test
    void smtp_strippedStartTls_refusesBeforeSendingCredentials() throws Exception {
        // A MITM that removes STARTTLS from the EHLO reply: the password must never go out.
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            JavaMailSenderImpl sender = sender(server.port());

            assertThatThrownBy(sender::testConnection)
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("STARTTLS");
            assertThat(server.received()).noneMatch(line -> line.startsWith("AUTH"));
        }
    }

    @Test
    void smtp_demoHostWithoutTls_stillAuthenticates() throws Exception {
        // Mailpit's case: plain SMTP, harness on → the demo path keeps working.
        demo.setEnabled(true);
        demo.setSmtpHost("localhost");
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            sender(server.port()).testConnection();

            assertThat(server.received()).anyMatch(line -> line.startsWith("AUTH"));
        }
    }

    private JavaMailSenderImpl sender(int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("localhost");
        sender.setPort(port);
        sender.setUsername("user@acme.co.uk");
        sender.setPassword("secret");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        policy.applySmtp(props, "localhost", port);
        return sender;
    }

    /** Plain-text SMTP server that never offers STARTTLS; records every command it receives. */
    private static final class FakeSmtpServer implements AutoCloseable {

        private final ServerSocket socket = new ServerSocket(0);
        private final List<String> received = new CopyOnWriteArrayList<>();
        private final Thread thread = new Thread(this::serve, "fake-smtp");

        FakeSmtpServer() throws IOException {
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        List<String> received() throws InterruptedException {
            thread.join(5000);
            return received;
        }

        private void serve() {
            try (Socket client = socket.accept();
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                 Writer out = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII)) {
                reply(out, "220 fake ESMTP");
                String line;
                while ((line = in.readLine()) != null) {
                    received.add(line);
                    String command = line.toUpperCase();
                    if (command.startsWith("EHLO")) {
                        reply(out, "250-fake\r\n250 AUTH PLAIN");
                    } else if (command.startsWith("AUTH")) {
                        reply(out, "235 ok");
                    } else if (command.startsWith("QUIT")) {
                        reply(out, "221 bye");
                        return;
                    } else {
                        reply(out, "250 ok");
                    }
                }
            } catch (IOException ignored) {
                // client went away — the test asserts on what was received
            }
        }

        private static void reply(Writer out, String text) throws IOException {
            out.write(text + "\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
