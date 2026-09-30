package com.smartfreight.inbox.config;

import com.smartfreight.inbox.service.MailTlsPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The platform SMTP sender, from the {@code smtp.*} settings (not {@code spring.mail.*}, which
 * hold the legacy env IMAP account) — used by tenants without their own SMTP mailbox. The
 * session/TLS profile comes from {@link MailTlsPolicy}.
 */
@Configuration
public class SmtpConfig {

    /** @param port SMTP port: 587 (STARTTLS) or 465 (SMTPS) */
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
