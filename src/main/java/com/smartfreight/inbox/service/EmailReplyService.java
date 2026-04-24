package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Сервис отправки ответов оператора клиентам по email.
 * 
 * Используется на финальном этапе workflow:
 * 1. Оператор просматривает черновик ответа (сгенерирован AI)
 * 2. Оператор нажимает "Отправить" в UI
 * 3. sendReply() отправляет письмо через SMTP на адрес отправителя
 * 4. Письмо помечается флагом In-Reply-To/References для корректного тредирования
 * 
 * Зависит от: SmtpConfig (JavaMailSender bean) и SMTP переменных окружения.
 */
@Service
public class EmailReplyService {

    private static final Logger log = LoggerFactory.getLogger(EmailReplyService.class);

    private final JavaMailSender mailSender;

    // Email адрес отправителя (из которого уходят ответы операторов)
    @Value("${smtp.username}")
    private String fromAddress;

    public EmailReplyService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Отправляет черновик ответа оператора обратно отправителю исходного письма.
     * 
     * Процесс:
     * 1. Создаёт MimeMessage для HTML/text
     * 2. Устанавливает заголовки In-Reply-To и References для тредирования (RFC 2822)
     * 3. Извлекает чистый email-адрес из поля senderEmail (обработка "Name <email>" формата)
     * 4. Отправляет через SMTP JavaMailSender
     * 
     * @param ticket тикет с данными письма: адрес отправителя, тема, черновик ответа
     * @throws RuntimeException если SMTP отправка не удалась (обёрнута в RuntimeException)
     */
    public void sendReply(EmailTicket ticket) {
        try {
            // Создаём MIME-письмо (поддерживает attachments и encoding)
            MimeMessage message = mailSender.createMimeMessage();
            // Helper упрощает работу с заголовками и телом письма
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            // Парсим адрес отправителя: "Name <email@example.com>" → "email@example.com"
            String to = extractEmail(ticket.getSenderEmail());
            helper.setFrom(fromAddress);
            helper.setTo(to);
            // Тема письма получает префикс "Re: " — стандарт для ответов
            helper.setSubject("Re: " + (ticket.getSubject() != null ? ticket.getSubject() : ""));
            // Текст письма — черновик ответа оператора
            helper.setText(ticket.getDraftReply() != null ? ticket.getDraftReply() : "");

            // Заголовки для корректного тредирования в почтовых клиентах (Gmail, Outlook, Thunderbird)
            // In-Reply-To указывает на исходное письмо (клиент автоматически группирует в разговор)
            // References — историческое поле для обратной совместимости со старыми клиентами
            if (ticket.getMessageId() != null) {
                message.setHeader("In-Reply-To", ticket.getMessageId());
                message.setHeader("References", ticket.getMessageId());
            }

            // Отправляем письмо через SMTP (синхронно, ждём результата)
            mailSender.send(message);
            log.info("Reply sent for ticket {} to {}", ticket.getId(), to);

        } catch (Exception e) {
            log.error("Failed to send reply for ticket {}: {}", ticket.getId(), e.getMessage());
            throw new RuntimeException("Failed to send email reply: " + e.getMessage(), e);
        }
    }

    /**
     * Извлекает чистый email-адрес из RFC 5321 формата "Display Name <user@example.com>".
     * 
     * Используется InternetAddress для корректной парсинга:
     * - "john@example.com" → "john@example.com"
     * - "John Doe <john@example.com>" → "john@example.com"
     * - "john+tag@example.com" → "john+tag@example.com"
     * 
     * Если парсинг не удался (невалидный format), возвращаем исходную строку как fallback.
     * 
     * @param raw строка в формате RFC 5321 или простой email
     * @return чистый email-адрес или пустую строку если null
     */
    private String extractEmail(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            // InternetAddress умеет парсить "Display Name <email>" → email
            return new InternetAddress(raw).getAddress();
        } catch (Exception e) {
            // Fallback: если что-то пошло не так — возвращаем исходное значение
            return raw;
        }
    }
}
