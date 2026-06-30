-- Phase 3.10: smart routing — AI urgency + sentiment on every classified email.
--
-- The classifier (one Qwen call, Phase 1.4) already returns relevance + business type. It now also
-- returns urgency and a negative-sentiment flag in the SAME call (TYPE|URGENCY|SENTIMENT), at no
-- extra LLM round-trip. These two signals drive prioritisation only — a tighter SLA window and
-- escalated-first list ordering — they never alter the AI draft.
--
-- urgency is stored as a SMALLINT (the Urgency enum's ORDINAL: LOW=0, NORMAL=1, HIGH=2) on purpose:
-- the operator queue is ordered by `urgency DESC` as a plain column, which only works with a numeric
-- ranking — a textual 'HIGH'/'LOW'/'NORMAL' would sort alphabetically and break the queue.
-- DEFAULT 1 (NORMAL) so existing rows and any classifier fail-open read as normal priority.
--
-- No index is added: the default list does a full-table sort (sentiment_negative DESC, urgency DESC,
-- created_at) which a partial index can't serve, and nothing filters on the escalated predicate yet.
-- An index would be added together with a future escalated-only / "needs attention" filter that uses it.
ALTER TABLE email_ticket
    ADD COLUMN urgency SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN sentiment_negative BOOLEAN NOT NULL DEFAULT FALSE;
