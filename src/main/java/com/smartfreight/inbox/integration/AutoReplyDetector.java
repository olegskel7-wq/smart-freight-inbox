package com.smartfreight.inbox.integration;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.util.Locale;

/**
 * Recognises automatic replies - out-of-office notes, vacation responders, delivery reports - from
 * their headers alone.
 *
 * <p>Such a message is not an enquiry: drafting an answer to it wastes an LLM call, and with auto-send
 * on, two responders answering each other is a mail loop. The classifier cannot be trusted with this -
 * an out-of-office note that quotes a shipping subject reads as freight mail to every model - so the
 * decision is made here, deterministically, before any model sees the text.
 *
 * <p>Headers only, never the subject line: "Out of office hours delivery?" is a customer asking a
 * question, and a missed enquiry is worse than a wasted draft.
 */
final class AutoReplyDetector {

    private AutoReplyDetector() {
    }

    /**
     * @return true when the headers mark the message as an automatic reply
     */
    static boolean isAutoReply(MimeMessage message) throws MessagingException {
        String autoSubmitted = header(message, "Auto-Submitted");
        // RFC 3834: the standard mark of an automatic response (vacation replies, delivery reports).
        if (autoSubmitted.startsWith("auto-replied")) {
            return true;
        }
        // "auto-generated" alone is any machine-written mail - a customer's system sending booking
        // requests is a real enquiry. Some servers (Exchange among them) put it on out-of-office
        // replies as well; what tells those apart is that they answer an earlier message.
        if (autoSubmitted.startsWith("auto-generated")
                && (!header(message, "In-Reply-To").isEmpty() || !header(message, "References").isEmpty())) {
            return true;
        }
        // Pre-standard marks still sent by common vacation responders.
        String autoreply = header(message, "X-Autoreply");
        return (!autoreply.isEmpty() && !autoreply.equals("no"))
                || !header(message, "X-Autorespond").isEmpty()
                || header(message, "Precedence").equals("auto_reply");
    }

    /** First value of a header, trimmed and lower-cased; empty when absent. */
    private static String header(MimeMessage message, String name) throws MessagingException {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 || values[0] == null
                ? ""
                : values[0].trim().toLowerCase(Locale.ROOT);
    }
}
