-- Phase 1.3: transactional outbox for the async AI pipeline (RabbitMQ).
--
-- Why an outbox: the ticket row (PostgreSQL) and the "process this ticket" message
-- (RabbitMQ) must not diverge. Writing the event into this table IN THE SAME
-- TRANSACTION as the ticket guarantees atomicity; OutboxRelay then publishes the
-- row to the broker and stamps published_at (at-least-once delivery — the consumer
-- is idempotent via the TicketStatus guard in TicketProcessingService).

CREATE TABLE outbox_event (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   BIGINT       NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    published_at   TIMESTAMP
);

-- The relay polls only unpublished rows; a partial index keeps that poll O(pending),
-- not O(history), as the table grows.
CREATE INDEX idx_outbox_event_unpublished ON outbox_event (id) WHERE published_at IS NULL;
