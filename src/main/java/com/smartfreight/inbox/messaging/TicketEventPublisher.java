package com.smartfreight.inbox.messaging;

/**
 * Seam between the business pipeline and the messaging infrastructure (Phase 1.3).
 *
 * Call sites (ingestion, retry scheduler) depend on this interface only — they do not
 * know whether events travel through a transactional outbox + RabbitMQ (current
 * implementation, {@link OutboxTicketEventPublisher}) or anything else. Same pattern
 * as {@code ItemLookupService}: swapping the transport is a new implementation,
 * not a pipeline rewrite.
 *
 * Contract: implementations must be called INSIDE an active transaction, so that
 * the event becomes durable atomically with the business change that caused it.
 */
public interface TicketEventPublisher {

    /** A new ticket was ingested and needs to go through the AI pipeline. */
    void publishTicketCreated(Long ticketId);

    /** A RETRY_LATER ticket was reset and needs another pipeline run. */
    void publishRetryRequested(Long ticketId);
}
