package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.tenancy.TenantContext;
import com.smartfreight.inbox.util.EmailAddressUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Answers "can we actually log in to this mailbox right now?" (Gate B4/B13).
 *
 * <p>{@code smartfreight_imap_adapters_active} and "running" on /settings only mean an adapter is
 * REGISTERED: a mailbox with a revoked app password or a broken certificate counts as running while
 * its IDLE loop fails on every reconnect, visible only as a WARN in the log — a silently dead inbox
 * is a failed pilot. Every few minutes this monitor logs in to each enabled mailbox with exactly the
 * adapter's JavaMail config ({@link MailboxImapAdapterManager#javaMailProperties}) and records the
 * outcome:
 * <ul>
 *   <li>{@code smartfreight_mailbox_up{tenant,mailbox}} 1/0 — alert {@code MailboxDown} says whose
 *       mailbox died (B13: tenant A's incident must not look like a platform outage);</li>
 *   <li>{@code smartfreight_mailbox_last_success_timestamp_seconds{tenant,mailbox}};</li>
 *   <li>the last error, address-masked, for the badge on /settings.</li>
 * </ul>
 * Labels are ids, never addresses. A login probe does not prove IDLE is delivering, but it catches
 * the failures that need a human: credentials, TLS, host.
 */
@Component
public class MailboxHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(MailboxHealthMonitor.class);

    /** Long enough for an SMTP/IMAP greeting with the reason, short enough for a badge tooltip. */
    private static final int MAX_ERROR_CHARS = 200;

    private final TenantMailboxRepository mailboxes;
    private final SecretCipher secretCipher;
    private final MailboxImapAdapterManager adapterManager;
    private final MultiGauge upGauge;
    private final MultiGauge lastSuccessGauge;

    /** mailbox id → outcome of its last probe. Bounded by the enabled-mailbox count. */
    private final Map<Long, MailboxHealth> health = new ConcurrentHashMap<>();

    public MailboxHealthMonitor(TenantMailboxRepository mailboxes,
                                SecretCipher secretCipher,
                                MailboxImapAdapterManager adapterManager,
                                MeterRegistry meterRegistry) {
        this.mailboxes = mailboxes;
        this.secretCipher = secretCipher;
        this.adapterManager = adapterManager;
        this.upGauge = MultiGauge.builder("smartfreight_mailbox_up")
                .description("1 if the last IMAP login probe of the mailbox succeeded, else 0")
                .register(meterRegistry);
        this.lastSuccessGauge = MultiGauge.builder("smartfreight_mailbox_last_success_timestamp_seconds")
                .description("Epoch seconds of the mailbox's last successful IMAP login probe")
                .register(meterRegistry);
    }

    /** Outcome of the last probe; {@code lastSuccessAt} is null until a probe has succeeded. */
    public record MailboxHealth(Long tenantId, boolean up, Instant checkedAt, Instant lastSuccessAt, String lastError) {
    }

    /** Health of one mailbox — empty until it has been probed (or while the key is missing). */
    public Optional<MailboxHealth> health(Long mailboxId) {
        return Optional.ofNullable(health.get(mailboxId));
    }

    /** Default 5 min; the first pass waits for the reconciler to start the adapters. */
    @Scheduled(fixedDelayString = "${mailbox.health-check-interval-ms:300000}", initialDelay = 60_000)
    public void probeAll() {
        if (!secretCipher.isConfigured()) {
            return; // no adapters run either — nothing to probe
        }
        List<TenantMailbox> enabled = TenantContext.callAsSystem(mailboxes::findByEnabledTrue);
        Set<Long> ids = enabled.stream().map(TenantMailbox::getId).collect(Collectors.toSet());
        health.keySet().retainAll(ids); // deleted / disabled mailboxes drop out of the metrics

        for (TenantMailbox mailbox : enabled) {
            record(mailbox, probe(mailbox));
        }
        publish();
    }

    /** Null on success, else the masked, trimmed reason. Never throws. */
    private String probe(TenantMailbox mailbox) {
        try {
            login(mailbox);
            return null;
        } catch (Exception e) {
            String reason = EmailAddressUtils.maskAddresses(String.valueOf(e.getMessage()));
            return reason.length() > MAX_ERROR_CHARS ? reason.substring(0, MAX_ERROR_CHARS) + "…" : reason;
        }
    }

    /** One IMAP login + logout with the adapter's own config. Overridden in tests. */
    void login(TenantMailbox mailbox) throws Exception {
        String password = secretCipher.decrypt(mailbox.getPasswordEnc());
        Session session = Session.getInstance(adapterManager.javaMailProperties(mailbox, password));
        try (Store store = session.getStore("imaps")) {
            store.connect(mailbox.getHost(), mailbox.getPort(), mailbox.getUsername(), password);
        }
    }

    private void record(TenantMailbox mailbox, String error) {
        Instant now = Instant.now();
        MailboxHealth previous = health.get(mailbox.getId());
        boolean up = error == null;
        Instant lastSuccess = up ? now : previous != null ? previous.lastSuccessAt() : null;
        health.put(mailbox.getId(), new MailboxHealth(mailbox.getTenantId(), up, now, lastSuccess, error));

        boolean wasUp = previous == null || previous.up();
        if (wasUp && !up) {
            log.warn("Mailbox {} (tenant {}) login FAILED: {}", mailbox.getId(), mailbox.getTenantId(), error);
        } else if (!wasUp && up) {
            log.info("Mailbox {} (tenant {}) login recovered", mailbox.getId(), mailbox.getTenantId());
        }
    }

    private void publish() {
        upGauge.register(health.entrySet().stream()
                .map(e -> MultiGauge.Row.of(tags(e), e.getValue().up() ? 1 : 0))
                .toList(), true);
        lastSuccessGauge.register(health.entrySet().stream()
                .filter(e -> e.getValue().lastSuccessAt() != null)
                .map(e -> MultiGauge.Row.of(tags(e), e.getValue().lastSuccessAt().getEpochSecond()))
                .toList(), true);
    }

    private static Tags tags(Map.Entry<Long, MailboxHealth> e) {
        return Tags.of("tenant", String.valueOf(e.getValue().tenantId()), "mailbox", String.valueOf(e.getKey()));
    }
}
