package com.smartfreight.inbox.config;

import com.smartfreight.inbox.service.MailTlsPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * SMTP outbound mail configuration.
 *
 * Separate configuration from IMAP: uses {@code smtp.*} parameters from application.yaml,
 * not {@code spring.mail.*} (which are reserved for inbound mail via ImapConfig).
 *
 * Configures TLS (via {@link MailTlsPolicy}) and connection timeouts.
 */
@Configuration
public class SmtpConfig {

    /**
     * JavaMailSender bean for sending messages via SMTP — the platform account, used by tenants
     * without their own SMTP mailbox.
     *
     * Configuration:
     * — TLS per {@link MailTlsPolicy}: 587 requires STARTTLS, 465 is implicit TLS, the server
     *   certificate and hostname are verified against the JVM truststore
     * — Connection timeouts (10 s) — Ollama does not block here, so limits are fixed
     *
     * @param port SMTP port: 587 (STARTTLS) or 465 (SMTPS)
     * @return configured JavaMailSender bean
     */
    @Bean
    public JavaMailSender javaMailSender(@Value("${smtp.host}") String host,
                                         @Value("${smtp.port:587}") int port,
                                         @Value("${smtp.username}") String username,
                                         @Value("${smtp.password}") String password,
                                         MailTlsPolicy mailTlsPolicy) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        mailTlsPolicy.applySmtp(sender.getJavaMailProperties(), host, port);
        return sender;
    }
}
