package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.enums.TicketType;

/**
 * Aggregation projection: how many tickets carry a given business type (Phase 2.1 analytics).
 *
 * Returned by the GROUP BY query in {@link EmailTicketRepository#countByTypeSince} via a JPQL
 * constructor expression. {@code type} is nullable — tickets that never received a classification
 * verdict (created pre-1.4, parked before classification, binary-prompt mode) group under null.
 */
public record TypeCount(TicketType type, Long count) {
}
