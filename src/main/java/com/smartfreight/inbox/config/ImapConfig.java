package com.smartfreight.inbox.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mail.dsl.Mail;
import org.springframework.messaging.MessageChannel;

/**
 * IMAP IDLE inbound mail configuration.
 *
 * Creates a Spring Integration pipeline:
 * IMAP server → adapter receives message → sends to mailChannel → EmailIngestionService processes it.
 *
 * If the configuration values are not set (host = imap.example.com),
 * the adapter is disabled to avoid pointless connection errors in a development environment.
 */
@Configuration
public class ImapConfig {

    private static final Logger log = LoggerFactory.getLogger(ImapConfig.class);

    @Value("${spring.mail.host}")
    private String host;

    @Value("${spring.mail.port:993}")
    private int port;

    @Value("${spring.mail.username}")
    private String username;

    @Value("${spring.mail.password}")
    private String password;

    /** Set IMAP_ENABLED=false to explicitly disable the adapter in environments without a mailbox. */
    @Value("${imap.enabled:true}")
    private boolean imapEnabled;

    /**
     * Synchronous message channel for incoming emails.
     *
     * DirectChannel — synchronous delivery: the sender waits until the receiver finishes processing.
     * The bean name "mailChannel" is referenced in @ServiceActivator(inputChannel = "mailChannel").
     *
     * @return configured DirectChannel
     */
    @Bean
    public MessageChannel mailChannel() {
        return new DirectChannel();
    }

    /**
     * IMAP IDLE pipeline — delivers new messages to mailChannel.
     *
     * IMAP IDLE: the server pushes notifications about new messages (push protocol, not polling).
     * This allows instant reaction, unlike polling (checking every N seconds).
     *
     * If the configuration values are not set (host = "imap.example.com" or password = "changeme"),
     * an empty flow is returned: mailChannel → nullChannel.
     * This is important in a development environment to avoid connection errors.
     *
     * @param mailChannel channel to which received messages are sent
     * @return configured IntegrationFlow
     */
    @Bean
    public IntegrationFlow imapIdleFlow(MessageChannel mailChannel) {
        boolean configured = imapEnabled
                && !host.equals("imap.example.com")
                && !password.equals("changeme");

        if (!configured) {
            log.warn("IMAP not configured — adapter disabled. Set IMAP_HOST, IMAP_USER, IMAP_PASSWORD env vars to enable (or IMAP_ENABLED=false to suppress this warning).");
            return IntegrationFlow.from(mailChannel).nullChannel();
        }

        String encodedUser = java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String encodedPassword = java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String url = String.format("imaps://%s:%s@%s:%d/INBOX", encodedUser, encodedPassword, host, port);

        return IntegrationFlow
                .from(Mail.imapIdleAdapter(url)
                        .javaMailProperties(p -> {
                            p.put("mail.store.protocol", "imaps");
                            p.put("mail.imaps.user", username);
                            p.put("mail.imaps.password", password);
                            p.put("mail.imaps.auth", "true");
                            p.put("mail.imaps.connectiontimeout", "10000");
                            p.put("mail.imaps.timeout", "60000");
                            p.put("mail.imaps.keepalive.interval", "60000");
                            p.put("mail.imaps.ssl.enable", "true");
                            p.put("mail.imaps.ssl.trust", host);
                            p.put("mail.imaps.partialfetch", "false");
                            p.put("mail.imaps.fetchsize", "1048576");
                        })
                        .shouldMarkMessagesAsRead(true)
                        .shouldDeleteMessages(false)
                        .autoStartup(true))
                .channel(mailChannel)
                .get();
    }
}
