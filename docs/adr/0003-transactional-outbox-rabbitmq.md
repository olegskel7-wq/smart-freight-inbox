# 3. Transactional outbox → RabbitMQ for the pipeline

**Status:** accepted · 2026-06

## Context
LLM calls take seconds to minutes. Running them on the IMAP thread blocked ingestion, and writing the
ticket and publishing a message separately (a dual write) can lose or duplicate work.

## Decision
The ticket and an `outbox_event` row are written in **one** DB transaction; `OutboxRelay` publishes
them with confirms to `ticket.events` → `q.ticket.process` (plus a DLQ). The consumer runs
2 × prefetch 1, matched to `OLLAMA_NUM_PARALLEL`, and is idempotent: it acts only on RECEIVED, NEW
and RETRY_LATER.

## Consequences
- The DB stays the single source of truth; the broker is only transport.
- At-least-once delivery is safe by construction.
- Stuck work is recovered twice: at startup, and at runtime by `StuckTicketSweeper`.
- The same outbox pattern is reused for webhooks (`webhook_delivery`).
