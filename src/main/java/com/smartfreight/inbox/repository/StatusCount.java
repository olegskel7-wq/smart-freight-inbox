package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.enums.TicketStatus;

/**
 * Aggregation projection: how many tickets hold a given status.
 *
 * Returned by the GROUP BY query in {@link EmailTicketRepository#countByStatusSince} via a JPQL
 * constructor expression — only two scalar columns travel from the database, never the entities
 * with their TEXT bodies.
 */
public record StatusCount(TicketStatus status, Long count) {
}
