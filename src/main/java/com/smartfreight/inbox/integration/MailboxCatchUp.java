package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-finds emails the IDLE adapter never turned into tickets (Gate B5) and ingests them.
 *
 * <p>The adapter is at-most-once: Spring Integration flags a message (\Seen or its own flag) when it
 * FETCHES it, before our handler persists it — so a DB outage, a crash or a redeploy mid-ingest loses
 * the email for good. And it only ever sees UNSEEN mail: an enquiry a client's colleague opened in
 * Gmail before we fetched it (during downtime, on a phone) is never ingested at all. Both are silent.
 *
 * <p>This turns ingestion at-least-once by reconciliation: after each health probe's login, it lists
 * INBOX messages received in the look-back window — never earlier than the mailbox was connected, so
 * onboarding a client does not turn their last days of handled mail into tickets — and ingests those
 * whose Message-ID has no ticket yet. The folder is opened READ-ONLY (the client's \Seen state is
 * theirs). Duplicates cannot arise: dedup by Message-ID, backed by the per-tenant unique index.
 *
 * <p>Every recovered email means IDLE missed it — counted per mailbox, alerted as
 * {@code MailboxCatchUpRecovering}.
 */
@Component
public class MailboxCatchUp {

    private static final Logger log = LoggerFactory.getLogger(MailboxCatchUp.class);

    /** Bounds one pass; a bigger backlog drains over the next passes. */
    private static final int MAX_PER_PASS = 200;

    private final EmailTicketRepository tickets;
    private final MailboxImapAdapterManager adapterManager;
    private final MeterRegistry meterRegistry;
    private final Duration lookBack;

    public MailboxCatchUp(EmailTicketRepository tickets,
                          MailboxImapAdapterManager adapterManager,
                          MeterRegistry meterRegistry,
                          @Value("${mailbox.catch-up-hours:48}") long lookBackHours) {
        this.tickets = tickets;
        this.adapterManager = adapterManager;
        this.meterRegistry = meterRegistry;
        this.lookBack = Duration.ofHours(lookBackHours);
    }

    /**
     * Ingests the window's emails that have no ticket yet, through the logged-in store.
     *
     * @return how many were ingested
     */
    public int run(TenantMailbox mailbox, Store store) throws MessagingException {
        Instant since = windowStart(mailbox, Instant.now());
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
        try {
            // IMAP SEARCH SINCE has day granularity — the exact cut-off is applied below.
            Message[] found = inbox.search(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)));
            FetchProfile profile = new FetchProfile();
            profile.add(FetchProfile.Item.ENVELOPE);
            profile.add(FetchProfile.Item.SIZE);
            profile.add("Message-ID");
            inbox.fetch(found, profile);

            Map<String, Message> candidates = new LinkedHashMap<>();
            for (Message message : found) {
                String messageId = messageId(message);
                Date received = message.getReceivedDate();
                if (messageId != null && received != null && !received.toInstant().isBefore(since)
                        && message.getSize() <= EmailIngestionService.MAX_EMAIL_BYTES) {
                    candidates.putIfAbsent(messageId, message);
                }
            }
            if (candidates.isEmpty()) {
                return 0;
            }

            Set<String> existing = new HashSet<>(TenantContext.callAs(mailbox.getTenantId(),
                    () -> tickets.findExistingMessageIds(candidates.keySet())));
            List<Message> missing = new ArrayList<>();
            candidates.forEach((id, message) -> {
                if (!existing.contains(id) && missing.size() < MAX_PER_PASS) {
                    missing.add(message);
                }
            });

            int ingested = 0;
            for (Message message : missing) {
                if (adapterManager.ingest(mailbox.getTenantId(), mailbox.getId(), (MimeMessage) message)) {
                    ingested++;
                }
            }
            if (ingested > 0) {
                log.warn("Mailbox {} (tenant {}): catch-up ingested {} email(s) the IDLE adapter missed",
                        mailbox.getId(), mailbox.getTenantId(), ingested);
                Counter.builder("smartfreight_mailbox_catchup_ingested_total")
                        .description("Emails ingested by mailbox catch-up — each one was missed by IDLE")
                        .tag("tenant", String.valueOf(mailbox.getTenantId()))
                        .tag("mailbox", String.valueOf(mailbox.getId()))
                        .register(meterRegistry)
                        .increment(ingested);
            }
            return ingested;
        } finally {
            inbox.close(false);
        }
    }

    /** Look-back window start, clamped to when the mailbox was connected. */
    Instant windowStart(TenantMailbox mailbox, Instant now) {
        Instant windowStart = now.minus(lookBack);
        if (mailbox.getCreatedAt() == null) {
            return windowStart;
        }
        Instant connected = mailbox.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
        return connected.isAfter(windowStart) ? connected : windowStart;
    }

    private static String messageId(Message message) throws MessagingException {
        String[] header = message.getHeader("Message-ID");
        return header == null || header.length == 0 ? null : header[0];
    }
}
