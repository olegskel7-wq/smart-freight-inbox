package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.ShipmentWatch;
import com.smartfreight.inbox.entity.TrackedItem;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.ShipmentWatchRepository;
import com.smartfreight.inbox.service.AiDraftingService;
import com.smartfreight.inbox.service.AutoSendService;
import com.smartfreight.inbox.service.ItemLookupService;
import com.smartfreight.inbox.service.SettingsService;
import com.smartfreight.inbox.tenancy.TenantContext;
import com.smartfreight.inbox.util.EmailAddressUtils;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Proactive status-update poller (Phase 2.4).
 *
 * <p>The product's default flow is reactive (a customer emails, the AI replies). This scheduler
 * adds the proactive direction: it polls the backend for every {@link ShipmentWatch} (a
 * subscription created from an inbound STATUS_UPDATE enquiry) and, when a shipment reaches a
 * milestone status, emails the customer an update without being asked — until the shipment is
 * DELIVERED, when the watch is deactivated.
 *
 * <p>Tenant model mirrors {@link RetryScheduler}: phase A reads every tenant's active watches in
 * the SYSTEM state; phase B processes each tenant inside its own {@link TenantContext} scope, so
 * the per-tenant feature flag, Ollama endpoint and RLS all apply, and the generated ticket is
 * stamped with the right tenant. The context wraps the transaction (TransactionTemplate inside
 * runAs, not {@code @Transactional}) — Hibernate fixes the session tenant at session open.
 *
 * <p>Send policy reuses the auto-send gate (Phase 1.6): a proactive update is delivered as an
 * outbound {@link EmailTicket} routed through {@link AutoSendService} exactly like an inbound
 * draft — auto-sent when the operator has enabled auto-send for STATUS_UPDATE, otherwise left as
 * a DRAFT_READY for review. The SMTP send happens AFTER the DB transaction commits (same
 * double-send safety as the reactive pipeline).
 */
@Component
public class StatusWatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatusWatchScheduler.class);

    /** Gate — like feature.auto-send it emails real customers, so the code default is OFF. */
    public static final String FEATURE_KEY = "feature.status-update-automation";

    /** Comma-separated backend status names that trigger a notification (V22 seed). */
    public static final String KEY_MILESTONES = "statusupdate.milestones";
    public static final String DEFAULT_MILESTONES = "AT_PORT,RELEASED,DELIVERED";

    /** Terminal status — once notified, the watch is done. */
    static final String DELIVERED = "DELIVERED";

    private final ShipmentWatchRepository watchRepository;
    private final EmailTicketRepository emailTicketRepository;
    private final ItemLookupService itemLookupService;
    private final AiDraftingService aiDraftingService;
    private final AutoSendService autoSendService;
    private final SettingsService settingsService;
    private final TransactionTemplate transactionTemplate;
    private final Counter sentCounter;
    private final Counter draftedCounter;

    public StatusWatchScheduler(ShipmentWatchRepository watchRepository,
                                EmailTicketRepository emailTicketRepository,
                                ItemLookupService itemLookupService,
                                AiDraftingService aiDraftingService,
                                AutoSendService autoSendService,
                                SettingsService settingsService,
                                TransactionTemplate transactionTemplate,
                                MeterRegistry meterRegistry) {
        this.watchRepository = watchRepository;
        this.emailTicketRepository = emailTicketRepository;
        this.itemLookupService = itemLookupService;
        this.aiDraftingService = aiDraftingService;
        this.autoSendService = autoSendService;
        this.settingsService = settingsService;
        this.transactionTemplate = transactionTemplate;
        this.sentCounter = Counter.builder("smartfreight_status_updates_total")
                .description("Proactive status updates emitted")
                .tag("result", "auto_sent")
                .register(meterRegistry);
        this.draftedCounter = Counter.builder("smartfreight_status_updates_total")
                .description("Proactive status updates emitted")
                .tag("result", "drafted")
                .register(meterRegistry);
    }

    /**
     * Polls all active watches and emits proactive updates for milestone changes.
     *
     * Schedule mirrors RetryScheduler: a fixed pause between runs and a startup delay so the
     * application is fully up first. The interval is configurable
     * ({@code statusupdate.poll-interval-ms}, default 2 minutes).
     */
    @Scheduled(fixedDelayString = "${statusupdate.poll-interval-ms:120000}", initialDelay = 60_000)
    public void pollWatches() {
        // Phase A — SYSTEM state: one read across every tenant's active subscriptions.
        List<ShipmentWatch> watches = TenantContext.callAsSystem(() ->
                transactionTemplate.execute(tx -> new ArrayList<>(watchRepository.findByActiveTrue())));

        if (watches == null || watches.isEmpty()) {
            return;
        }

        // Phase B — per tenant: each watch processed inside its owning tenant's scope.
        Map<Long, List<Long>> idsByTenant = watches.stream()
                .collect(Collectors.groupingBy(ShipmentWatch::getTenantId,
                        Collectors.mapping(ShipmentWatch::getId, Collectors.toList())));

        idsByTenant.forEach((tenantId, watchIds) ->
                TenantContext.runAs(tenantId, () -> processTenant(watchIds)));
    }

    /** Processes one tenant's watches in its context. Skipped entirely when the flag is off. */
    private void processTenant(List<Long> watchIds) {
        if (!settingsService.isFeatureEnabled(FEATURE_KEY, false)) {
            return;
        }
        Set<String> milestones = milestones();
        if (milestones.isEmpty()) {
            return;
        }

        for (Long watchId : watchIds) {
            try {
                NotifyOutcome outcome =
                        transactionTemplate.execute(tx -> prepareNotification(watchId, milestones));
                if (outcome == null) {
                    continue;
                }
                // After the DB transaction committed (same ordering as the reactive pipeline):
                // an SMTP failure now leaves a safe DRAFT_READY ticket, never a half-sent update.
                if (outcome.eligible()) {
                    autoSendService.autoSend(outcome.ticketId());
                    sentCounter.increment();
                } else {
                    draftedCounter.increment();
                }
            } catch (CallNotPermittedException e) {
                // The tenant's Ollama breaker is open — drafting was not attempted, the
                // transaction rolled back (no ticket, watch not advanced). Retry next poll.
                log.warn("Ollama breaker open — skipping watch {} this run", watchId);
            } catch (Exception e) {
                log.error("Failed to process shipment watch {}: {}", watchId, EmailAddressUtils.maskAddresses(e.getMessage()));
            }
        }
    }

    /**
     * In-transaction work for one watch: poll the backend, decide whether a milestone was hit,
     * and if so draft + persist a proactive ticket and advance the watch. Returns null when there
     * is nothing to send (no data, no change, or an intermediate status).
     */
    private NotifyOutcome prepareNotification(Long watchId, Set<String> milestones) {
        ShipmentWatch watch = watchRepository.findById(watchId).orElse(null);
        if (watch == null || !watch.isActive()) {
            return null;
        }

        TrackedItem item = itemLookupService.findByIdentifier(watch.getIdentifier()).orElse(null);
        if (item == null) {
            return null; // backend unavailable or item gone — try again next poll
        }

        String current = item.getStatus();
        if (current == null
                || current.equals(watch.getLastNotifiedStatus())
                || !milestones.contains(current)) {
            return null; // no change since the last notice, or an intermediate (silent) status
        }

        String previousStatus = watch.getLastNotifiedStatus();
        // May throw CallNotPermittedException (breaker open) — propagated to roll the TX back.
        String draft = aiDraftingService.generateProactiveUpdate(item, previousStatus);

        EmailTicket ticket = EmailTicket.builder()
                // Synthetic Message-ID: the column is NOT NULL with a per-tenant unique constraint,
                // and a proactive update belongs to no inbound thread. UUID keeps repeat notices
                // about the same shipment distinct.
                .messageId("<proactive-" + UUID.randomUUID() + "@smartfreight>")
                .senderEmail(watch.getCustomerEmail())
                .subject("Shipment update: " + watch.getIdentifier())
                .body("Proactive status update: " + watch.getIdentifier() + " → " + current)
                .extractedIdentifier(watch.getIdentifier())
                // Sent through the inbox the customer's status enquiry came in on.
                .mailboxId(watch.getMailboxId())
                .ticketType(TicketType.STATUS_UPDATE)
                .draftReply(draft)
                // type specific + identifier present + item found, no rate → 0.9 (clears 0.8).
                .aiConfidence(autoSendService.confidenceFor(TicketType.STATUS_UPDATE, true, true, false))
                .status(TicketStatus.DRAFT_READY)
                .proactive(true)
                .build();
        emailTicketRepository.save(ticket);

        boolean eligible = autoSendService.shouldAutoSend(ticket);

        watch.setLastNotifiedStatus(current);
        if (DELIVERED.equals(current)) {
            watch.setActive(false); // terminal — stop polling this shipment
        }
        watch.setUpdatedAt(LocalDateTime.now());
        watchRepository.save(watch);

        log.info("Proactive update for {} ({} → {}), ticket {} — {}",
                watch.getIdentifier(), previousStatus, current, ticket.getId(),
                eligible ? "auto-sending" : "left as draft");
        return new NotifyOutcome(ticket.getId(), eligible);
    }

    /** Milestone status names from settings; parsed tolerantly like {@code AutoSendService}. */
    Set<String> milestones() {
        String raw = settingsService.getString(KEY_MILESTONES, DEFAULT_MILESTONES);
        Set<String> result = new java.util.HashSet<>();
        for (String name : raw.split(",")) {
            String trimmed = name.trim().toUpperCase();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** Result of preparing one notification: the saved ticket and whether it should auto-send. */
    private record NotifyOutcome(Long ticketId, boolean eligible) {
    }
}
