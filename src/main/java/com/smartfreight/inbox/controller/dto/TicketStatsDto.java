package com.smartfreight.inbox.controller.dto;

/**
 * At-a-glance counts for the {@code /tickets} summary strip (Phase 3 UI refresh). Computed on
 * request and tenant-scoped, mirroring how the analytics KPIs are derived — no stored state.
 *
 * @param open          actionable backlog (tickets outside the terminal CLOSED/AUTO_SENT/IRRELEVANT)
 * @param awaitingReview drafts ready for an operator (DRAFT_READY)
 * @param slaBreached    open tickets past their SLA deadline
 * @param autoSentToday  replies auto-sent since the start of today
 */
public record TicketStatsDto(long open, long awaitingReview, long slaBreached, long autoSentToday) {
}
