package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reads tickets for the operator API: one ticket, the queue list with its filters, the summary-strip
 * counts and the ids a reprocess re-enqueues. Every query is tenant-scoped ({@code @TenantId} + RLS),
 * so a ticket of another tenant is simply not found.
 */
@Service
public class TicketQueryService {

    /** Actionable backlog for the summary strip — every status outside the terminal three. */
    private static final Set<TicketStatus> OPEN_STATUSES = EnumSet.of(
            TicketStatus.RECEIVED, TicketStatus.NEW, TicketStatus.PROCESSING,
            TicketStatus.DRAFT_READY, TicketStatus.RETRY_LATER, TicketStatus.FAILED);

    /**
     * Statuses a manual reprocess re-enqueues. RECEIVED is included as a manual unstick path: its
     * consumer re-runs classification, so re-enqueueing a ticket whose original event is still in
     * flight stays idempotent.
     */
    private static final List<TicketStatus> REPROCESSABLE_STATUSES =
            List.of(TicketStatus.RECEIVED, TicketStatus.NEW, TicketStatus.RETRY_LATER);

    /** The four summary-strip counts. */
    public record TicketCounts(long open, long awaitingReview, long slaBreached, long autoSentToday) {}

    private final EmailTicketRepository emailTicketRepository;

    public TicketQueryService(EmailTicketRepository emailTicketRepository) {
        this.emailTicketRepository = emailTicketRepository;
    }

    public Optional<EmailTicket> find(Long id) {
        return emailTicketRepository.findById(id);
    }

    /**
     * One page of the queue. A summary-chip {@code view} ({@code open}, {@code awaiting},
     * {@code breached}, {@code autosent_today}) takes precedence over the other filters and reuses
     * the exact set its chip counts, so the count and the list it links to never diverge. Otherwise
     * {@code assignee} scopes the shared queue — {@code mine} (the caller's tickets; empty without a
     * caller), {@code unassigned}, anything else = all — and {@code status} narrows it when it names
     * a status (an unknown name is ignored). Sort and paging come with the pageable.
     */
    public Page<EmailTicket> list(String view, String status, String assignee, String currentUserId,
                                  Pageable pageable) {
        Page<EmailTicket> viewPage = resolveView(view, pageable);
        return viewPage != null ? viewPage : filteredPage(status, assignee, currentUserId, pageable);
    }

    /** The summary-strip counts; each matches its {@code view} in {@link #list}. */
    public TicketCounts counts() {
        return new TicketCounts(
                emailTicketRepository.countByStatusIn(OPEN_STATUSES),
                emailTicketRepository.countByStatus(TicketStatus.DRAFT_READY),
                emailTicketRepository.countByStatusInAndSlaBreachedTrue(OPEN_STATUSES),
                emailTicketRepository.countByStatusAndUpdatedAtGreaterThanEqual(
                        TicketStatus.AUTO_SENT, LocalDate.now().atStartOfDay()));
    }

    /** Ids of the current tenant's tickets a manual reprocess re-enqueues (id projection only). */
    public List<Long> reprocessableTicketIds() {
        return emailTicketRepository.findIdsByStatusIn(REPROCESSABLE_STATUSES);
    }

    /** The status/assignee-filtered page: {@code mine}, {@code unassigned} or everything. */
    private Page<EmailTicket> filteredPage(String status, String assignee, String currentUserId,
                                           Pageable pageable) {
        TicketStatus statusFilter = parseStatusOrNull(status);
        String mode = assignee == null ? "all" : assignee.trim().toLowerCase(Locale.ROOT);
        if ("mine".equals(mode)) {
            return (currentUserId == null)
                    ? Page.empty(pageable)
                    : emailTicketRepository.findByAssignee(currentUserId, statusFilter, pageable);
        } else if ("unassigned".equals(mode)) {
            return emailTicketRepository.findUnassigned(statusFilter, pageable);
        } else if (statusFilter != null) {
            return emailTicketRepository.findByStatus(statusFilter, pageable);
        }
        return emailTicketRepository.findAll(pageable);
    }

    /**
     * Resolves a summary-chip drill-down to a page, or null when no recognised view is
     * requested (the caller then falls back to the normal status/assignee filtering). Each case
     * reuses the same definition as its counterpart count in {@link #counts()}.
     */
    private Page<EmailTicket> resolveView(String view, Pageable pageable) {
        if (view == null || view.isBlank()) {
            return null;
        }
        return switch (view.trim().toLowerCase(Locale.ROOT)) {
            case "open" -> emailTicketRepository.findByStatusIn(OPEN_STATUSES, pageable);
            case "awaiting" -> emailTicketRepository.findByStatus(TicketStatus.DRAFT_READY, pageable);
            case "breached" -> emailTicketRepository.findByStatusInAndSlaBreachedTrue(OPEN_STATUSES, pageable);
            case "autosent_today" -> emailTicketRepository.findByStatusAndUpdatedAtGreaterThanEqual(
                    TicketStatus.AUTO_SENT, LocalDate.now().atStartOfDay(), pageable);
            default -> null;
        };
    }

    private TicketStatus parseStatusOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TicketStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
