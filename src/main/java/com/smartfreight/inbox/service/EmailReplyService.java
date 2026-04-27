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
 * Operatoriaus atsakymų siuntimo el. paštu paslauga.
 *
 * Naudojama paskutiniame darbo eigos žingsnyje:
 * 1. Operatorius peržiūri AI sugeneruotą juodraštį
 * 2. Operatorius spaudžia "Send" in the UI
 * 3. {@link #sendReply} išsiunčia laišką per SMTP į siuntėjo adresą
 * 4. Laiške nustatomi In-Reply-To/References antraštės teisingam sriegimui
 *
 * Priklausomybės: {@link com.smartfreight.inbox.config.SmtpConfig} (JavaMailSender bean)
 * ir SMTP aplinkos kintamieji.
 */
@Service
public class EmailReplyService {

    private static final Logger log = LoggerFactory.getLogger(EmailReplyService.class);

    private final JavaMailSender mailSender;

    /** El. pašto siuntėjo adresas (iš kurio išsiunčiami operatorių atsakymai). */
    @Value("${smtp.username}")
    private String fromAddress;

    public EmailReplyService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Išsiunčia operatoriaus juodraštį atgal pradinio laiško siuntėjui.
     *
     * Algoritmas:
     * 1. Sukuriamas MimeMessage HTML/text turiniui
     * 2. Nustatomi In-Reply-To ir References antraštės sriegimui (RFC 2822)
     * 3. Iš senderEmail ištraukiamas švaras el. pašto adresas (apdorojamas "Vardas <email>" formatas)
     * 4. Laiškas išsiunčiamas per SMTP sinchroniškai
     *
     * @param ticket bilietas su laiško duomenimis: siuntėjo adresas, tema, juodraštis
     * @throws RuntimeException jei SMTP siuntimas nepavyko
     */
    public void sendReply(EmailTicket ticket) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            // Ištraukiame švarų adresą: "Vardas <email@example.com>" → "email@example.com"
            String to = extractEmail(ticket.getSenderEmail());
            helper.setFrom(fromAddress);
            helper.setTo(to);
            // "Re: " prefiksas – standartas atsakomiesiems laiškams
            helper.setSubject("Re: " + (ticket.getSubject() != null ? ticket.getSubject() : ""));
            helper.setText(ticket.getDraftReply() != null ? ticket.getDraftReply() : "");

            // In-Reply-To ir References leidžia pašto klientams (Gmail, Outlook, Thunderbird)
            // automatiškai sugrupuoti laišką į pokalbio gijoje
            if (ticket.getMessageId() != null) {
                message.setHeader("In-Reply-To", ticket.getMessageId());
                message.setHeader("References", ticket.getMessageId());
            }

            mailSender.send(message);
            log.info("Reply sent for ticket {} to {}", ticket.getId(), to);

        } catch (Exception e) {
            log.error("Failed to send reply for ticket {}: {}", ticket.getId(), e.getMessage());
            throw new RuntimeException("Failed to send email reply: " + e.getMessage(), e);
        }
    }

    /**
     * Ištraukia švarų el. pašto adresą iš RFC 5321 formato.
     *
     * Pavyzdžiai:
     * – {@code "john@example.com"} → {@code "john@example.com"}
     * – {@code "John Doe <john@example.com>"} → {@code "john@example.com"}
     * – {@code "john+tag@example.com"} → {@code "john+tag@example.com"}
     *
     * Jei analizė nepavyksta (netinkamas formatas) – grąžinama pradinė eilutė (atsarginis variantas).
     *
     * @param raw eilutė RFC 5321 formate arba paprastas el. paštas
     * @return švaras el. pašto adresas arba tuščia eilutė jei null
     */
    private String extractEmail(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            return new InternetAddress(raw).getAddress();
        } catch (Exception e) {
            return raw;
        }
    }
}
