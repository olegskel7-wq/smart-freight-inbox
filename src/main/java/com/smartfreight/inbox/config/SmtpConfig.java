package com.smartfreight.inbox.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * Конфигурация SMTP отправителя для отправки ответов по email.
 * 
 * Отдельная конфигурация от IMAP: используются параметры smtp.* из application.yaml,
 * не spring.mail.* (которые зарезервированы для входящей почты через ImapConfig).
 * 
 * Spring Boot автоматически отключает стандартную JavaMailSender когда обнаруживает
 * этот bean с @Configuration — позволяет нам полностью контролировать SMTP.
 */
@Configuration
public class SmtpConfig {

    // SMTP сервер (например smtp.gmail.com)
    @Value("${smtp.host}")
    private String host;

    // Порт SMTP: 587 (STARTTLS) или 465 (SMTPS)
    @Value("${smtp.port:587}")
    private int port;

    // Учётные данные отправителя (email адрес или App Password для Gmail)
    @Value("${smtp.username}")
    private String username;

    @Value("${smtp.password}")
    private String password;

    /**
     * Создаёт bean JavaMailSender для отправки писем на SMTP.
     * 
     * Конфигурация включает:
     * - STARTTLS для шифрования соединения (стандарт для порта 587)
     * - Таймауты соединения (10 сек) — Ollama не блокирует, поэтому таймауты фиксированные
     * - SSL trust для домена SMTP сервера — необходимо для Microsoft JDK 17
     * 
     * @return настроенный JavaMailSender bean
     */
    @Bean
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);

        Properties props = sender.getJavaMailProperties();
        // Используем SMTP протокол (не EWS или другие)
        props.put("mail.transport.protocol", "smtp");
        // Требуется аутентификация перед отправкой
        props.put("mail.smtp.auth", "true");
        // STARTTLS: сначала открыть незащищённое соединение, затем перейти на TLS
        props.put("mail.smtp.starttls.enable", "true");
        // Доверяем сертификату SMTP хоста (исправляет SSLHandshakeException на Microsoft JDK)
        props.put("mail.smtp.ssl.trust", host);
        // Таймауты в мс (10 сек достаточно — SMTP сервер отвечает быстро)
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        return sender;
    }
}
