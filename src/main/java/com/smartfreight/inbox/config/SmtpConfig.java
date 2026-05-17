package com.smartfreight.inbox.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * SMTP outbound mail configuration.
 *
 * Separate configuration from IMAP: uses {@code smtp.*} parameters from application.yaml,
 * not {@code spring.mail.*} (which are reserved for inbound mail via ImapConfig).
 *
 * Configures STARTTLS encryption, connection timeouts, and SSL trust.
 */
@Configuration
public class SmtpConfig {

    @Value("${smtp.host}")
    private String host;

    /** SMTP port: 587 (STARTTLS) or 465 (SMTPS). */
    @Value("${smtp.port:587}")
    private int port;

    @Value("${smtp.username}")
    private String username;

    @Value("${smtp.password}")
    private String password;

    /**
     * JavaMailSender bean for sending messages via SMTP.
     *
     * Configuration:
     * — STARTTLS for connection encryption (standard for port 587)
     * — Connection timeouts (10 s) — Ollama does not block here, so limits are fixed
     * — SSL trust for the SMTP server's domain — required on Microsoft JDK 17
     *
     * @return configured JavaMailSender bean
     */
    @Bean
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.ssl.trust", host);
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        return sender;
    }
}
