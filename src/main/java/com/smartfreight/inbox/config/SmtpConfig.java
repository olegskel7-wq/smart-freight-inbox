package com.smartfreight.inbox.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * SMTP siunčiamo pašto konfigūracija.
 *
 * Atskira konfigūracija nuo IMAP: naudojami {@code smtp.*} parametrai iš application.yaml,
 * ne {@code spring.mail.*} (kurie rezervuoti gaunamiems laiškams per ImapConfig).
 *
 * Sukonfigūruoja STARTTLS šifravimą, jungimosi laiko limitus ir SSL pasitikėjimą.
 */
@Configuration
public class SmtpConfig {

    @Value("${smtp.host}")
    private String host;

    /** SMTP prievadas: 587 (STARTTLS) arba 465 (SMTPS). */
    @Value("${smtp.port:587}")
    private int port;

    @Value("${smtp.username}")
    private String username;

    @Value("${smtp.password}")
    private String password;

    /**
     * JavaMailSender bean'as laiškų siuntimui per SMTP.
     *
     * Konfigūracija:
     * – STARTTLS ryšio šifravimui (standartas 587 prievadui)
     * – Jungimosi laiko limitai (10 s) – Ollama neblokuoja, todėl limitai fiksuoti
     * – SSL pasitikėjimas SMTP serverio domenui – būtina Microsoft JDK 17
     *
     * @return sukonfigūruotas JavaMailSender bean'as
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
        // Autentifikacija reikalinga prieš siuntimą
        props.put("mail.smtp.auth", "true");
        // STARTTLS: pirmiausia atviras ryšys, tada pereinama prie TLS
        props.put("mail.smtp.starttls.enable", "true");
        // Pasitikime SMTP serverio sertifikatu (ištaiso SSLHandshakeException Microsoft JDK)
        props.put("mail.smtp.ssl.trust", host);
        // Laiko limitai ms (10 s pakanka – SMTP serveris atsako greitai)
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        return sender;
    }
}
