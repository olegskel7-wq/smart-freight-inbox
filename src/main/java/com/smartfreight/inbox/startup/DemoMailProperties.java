package com.smartfreight.inbox.startup;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo mail harness settings (Phase 4.8 S2), bound from the {@code demo.mail.*} keys.
 *
 * <p>When {@link #enabled}, {@link DemoMailboxBootstrap} points the demo tenants' mailboxes at
 * the fake mail servers from the compose {@code demo} profile: GreenMail for inbound IMAPS,
 * Mailpit for outbound SMTP (its web UI is the "customer's inbox" during a demo). Hosts
 * default to the compose service names; a backend run from the IDE uses {@code localhost}.
 */
@Component
@ConfigurationProperties(prefix = "demo.mail")
@Getter
@Setter
public class DemoMailProperties {

    /** Master switch. Off = demo mailboxes (if any were created earlier) are removed. */
    private boolean enabled = false;

    /** GreenMail IMAPS endpoint (self-signed cert — trusted per host by the IMAP adapter). */
    private String imapHost = "greenmail";
    private int imapPort = 3993;

    /** Mailpit SMTP endpoint — accepts any credentials, catches every reply. */
    private String smtpHost = "mailpit";
    private int smtpPort = 1025;

    /** Password stored for the demo accounts. GreenMail runs with auth disabled, so any value works. */
    private String password = "demo";

    /**
     * Tenant id → the demo inbox addresses that tenant watches, one adapter each. Several per
     * tenant show the multi-inbox behaviour: every reply leaves FROM the inbox it arrived on.
     */
    private Map<Long, List<String>> inboxes = new LinkedHashMap<>();
}
