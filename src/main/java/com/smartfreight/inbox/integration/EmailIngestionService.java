package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

// Точка входа для входящих писем: получает MimeMessage из IMAP IDLE канала,
// выполняет дедупликацию, сохраняет тикет и запускает AI-конвейер.
@Service
public class EmailIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EmailIngestionService.class);

    private final EmailTicketRepository emailTicketRepository;
    private final TicketProcessingService ticketProcessingService;

    public EmailIngestionService(EmailTicketRepository emailTicketRepository,
                                 TicketProcessingService ticketProcessingService) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketProcessingService = ticketProcessingService;
    }

    // @ServiceActivator — Spring Integration вызывает этот метод автоматически
    // при появлении нового письма в канале "mailChannel"
    @ServiceActivator(inputChannel = "mailChannel")

    // @Transactional — сохранение тикета и запуск обработки атомарны.
    // Если save() бросит исключение — тикет не создастся и обработка не запустится.
    @Transactional
    public void processIncomingEmail(MimeMessage mimeMessage) {
        try {
            // Читаем уникальный идентификатор письма из заголовка Message-ID (стандарт RFC 2822)
            String[] messageIds = mimeMessage.getHeader("Message-ID");

            if (messageIds == null || messageIds.length == 0) {
                log.warn("Incoming email has no Message-ID header, skipping");
                return;
            }

            String messageId = messageIds[0];
            MDC.put("messageId", messageId);

            // Дедупликация: одно письмо IMAP может доставить дважды после переподключения
            if (emailTicketRepository.findByMessageId(messageId).isPresent()) {
                log.info("Duplicate email ignored: {}", messageId);
                return;
            }

            // Извлекаем тело письма (поддерживаем plain text и multipart)
            String body = extractBody(mimeMessage);

            // Извлекаем отправителя (берём первый адрес из From)
            String senderEmail = "";
            if (mimeMessage.getFrom() != null && mimeMessage.getFrom().length > 0) {
                senderEmail = mimeMessage.getFrom()[0].toString();
            }

            // Создаём тикет через Builder — поля по умолчанию: status=NEW, retryCount=0
            EmailTicket ticket = EmailTicket.builder()
                    .messageId(messageId)
                    .subject(mimeMessage.getSubject())
                    .senderEmail(senderEmail)
                    .body(body)
                    .build();

            try {
                EmailTicket saved = emailTicketRepository.save(ticket);
                MDC.put("ticketId", String.valueOf(saved.getId()));
                log.info("Email ingested: ticket ID={}, subject='{}'",
                        saved.getId(), saved.getSubject());

                // Запускаем AI-конвейер асинхронно (не блокируем IMAP поток).
                // process() помечен @Async — возвращает управление немедленно.
                // Передаём только ID (не объект) — паттерн "Pass ID, not entity".
                ticketProcessingService.process(saved.getId());

            } catch (DataIntegrityViolationException e) {
                // Race condition: два потока одновременно прошли проверку findByMessageId
                // и оба попытались сохранить. Уникальный индекс отклонил второй — ок.
                log.info("Duplicate email on concurrent save, skipping: {}", messageId);
            }

        } catch (MessagingException | IOException e) {
            log.error("Failed to parse incoming email", e);
        } finally {
            MDC.remove("messageId");
            MDC.remove("ticketId");
        }
    }

    // Извлекает текстовое тело письма.
    // Обрабатывает три случая:
    //   1. Простое текстовое письмо (String)
    //   2. Multipart письмо — берём первую text/plain часть
    //   3. Остальное — возвращаем пустую строку
    private String extractBody(MimeMessage message) throws MessagingException, IOException {
        Object content = message.getContent();

        // Случай 1: тело уже является строкой (plain text письмо)
        if (content instanceof String text) {
            return text;
        }

        // Случай 2: multipart (например text/plain + text/html + вложения)
        if (content instanceof Multipart multipart) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                // Берём только text/plain части, пропускаем HTML и вложения
                if (part.isMimeType("text/plain")) {
                    sb.append(part.getContent());
                    sb.append("\n");
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
            // Если text/plain не нашли — пробуем взять хотя бы первую часть
            if (multipart.getCount() > 0) {
                Object firstPart = multipart.getBodyPart(0).getContent();
                if (firstPart instanceof String s) {
                    return s;
                }
            }
        }

        log.warn("Could not extract text body from email, using empty string");
        return "";
    }
}
