package com.smartfreight.inbox.messaging;

/**
 * Lightweight real-time notification that a ticket changed (Phase 3.2), broadcast to the per-tenant
 * topic {@code /topic/tickets/{tenantId}} and consumed by the React {@code /tickets} list.
 *
 * <p>Deliberately minimal: it carries the ticket id and its new status only, as a signal. The SPA
 * re-fetches its current (filtered/sorted/paginated) query on receipt rather than merging this
 * payload into the list, so what it shows always matches the active query — the id/status are there
 * for cheap client-side hints (e.g. a future toast), not as the source of truth for the row.
 */
public record TicketLiveEvent(Long id, String status) {
}
