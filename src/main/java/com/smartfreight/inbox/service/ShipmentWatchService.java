package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.ShipmentWatch;
import com.smartfreight.inbox.entity.TrackedItem;
import com.smartfreight.inbox.repository.ShipmentWatchRepository;
import com.smartfreight.inbox.util.EmailAddressUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Maintains proactive status-update subscriptions (Phase 2.4).
 *
 * <p>The inbound enquiry is the subscription: when the pipeline classifies a STATUS_UPDATE
 * ticket whose identifier was found in the backend, {@link #upsertFromTicket} records (or
 * refreshes) a {@link ShipmentWatch}. {@code StatusWatchScheduler} then drives the proactive
 * emails. Runs inside the ticket's tenant context, so {@code @TenantId} stamps the owning
 * tenant automatically.
 */
@Service
public class ShipmentWatchService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentWatchService.class);

    private final ShipmentWatchRepository watchRepository;

    public ShipmentWatchService(ShipmentWatchRepository watchRepository) {
        this.watchRepository = watchRepository;
    }

    /**
     * Records or refreshes a subscription from a freshly answered STATUS_UPDATE ticket.
     *
     * <p>{@code lastNotifiedStatus} is set to the status that was just looked up — the reactive
     * reply already informed the customer of it, so the poller must not immediately re-notify
     * the same value. A re-enquiry reactivates a previously completed (DELIVERED) watch.
     *
     * @param ticket the answered ticket (provides the recipient address and identifier)
     * @param item   the looked-up item (provides the current status to seed against)
     */
    public void upsertFromTicket(EmailTicket ticket, TrackedItem item) {
        String identifier = ticket.getExtractedIdentifier();
        String email = EmailAddressUtils.extract(ticket.getSenderEmail());
        if (identifier == null || identifier.isBlank() || email == null || email.isBlank()) {
            return;
        }

        ShipmentWatch watch = watchRepository.findByIdentifierAndCustomerEmail(identifier, email)
                .orElseGet(() -> {
                    ShipmentWatch w = new ShipmentWatch();
                    w.setIdentifier(identifier);
                    w.setCustomerEmail(email);
                    return w;
                });
        watch.setLastNotifiedStatus(item.getStatus());
        // Proactive updates follow the latest enquiry's inbox; a ticket without one keeps the old.
        if (ticket.getMailboxId() != null) {
            watch.setMailboxId(ticket.getMailboxId());
        }
        watch.setActive(true);
        watch.setUpdatedAt(LocalDateTime.now());
        watchRepository.save(watch);
        log.info("Shipment watch upserted: identifier={}, seededStatus={}", identifier, item.getStatus());
    }
}
