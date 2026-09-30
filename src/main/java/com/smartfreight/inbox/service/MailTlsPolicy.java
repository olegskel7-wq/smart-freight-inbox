package com.smartfreight.inbox.service;

import com.smartfreight.inbox.startup.DemoMailProperties;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * TLS rules for every JavaMail connection the platform opens — tenant IMAP adapters, tenant and
 * platform SMTP senders, the Gmail Drafts append.
 *
 * <p>Real mail servers are verified like a browser would: certificate chain against the JVM
 * truststore AND the hostname ({@code ssl.checkserveridentity}), no {@code ssl.trust} — that
 * property makes Angus Mail accept ANY certificate the named host presents, so a network MITM
 * could read the IMAP LOGIN / SMTP AUTH password. SMTP on 465 is implicit TLS; any other port
 * must upgrade with STARTTLS ({@code starttls.required}) — opportunistic STARTTLS alone lets a
 * MITM strip the upgrade and receive AUTH in cleartext.
 *
 * <p>The ONLY relaxation is for the demo harness's own fake servers (Phase 4.8 S2), and only
 * while the harness is on: GreenMail's certificate is self-signed and minted at start (it still
 * names the host, so the hostname check stays), Mailpit has no TLS at all. The match is exact on
 * the configured demo host, so it is scoped per mailbox — a real mailbox is never relaxed, and
 * with the harness off nothing is.
 */
@Component
public class MailTlsPolicy {

    /** SMTPS — TLS from the first byte; every other SMTP port must STARTTLS. */
    static final int SMTPS_PORT = 465;

    private final DemoMailProperties demo;

    public MailTlsPolicy(DemoMailProperties demo) {
        this.demo = demo;
    }

    /** TLS properties for an {@code imaps} store on {@code host}. */
    public void applyImaps(Properties props, String host) {
        props.put("mail.imaps.ssl.checkserveridentity", "true");
        if (isDemo(host, demo.getImapHost())) {
            props.put("mail.imaps.ssl.trust", host);
        }
    }

    /** TLS properties for the {@code smtp} transport to {@code host:port}. */
    public void applySmtp(Properties props, String host, int port) {
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        if (isDemo(host, demo.getSmtpHost())) {
            // Mailpit speaks plain SMTP: STARTTLS stays opportunistic, never required.
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.ssl.trust", host);
        } else if (port == SMTPS_PORT) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
    }

    private boolean isDemo(String host, String demoHost) {
        return demo.isEnabled() && host != null && host.equalsIgnoreCase(demoHost);
    }
}
