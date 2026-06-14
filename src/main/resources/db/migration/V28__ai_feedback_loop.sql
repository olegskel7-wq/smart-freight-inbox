-- Phase 3.1: AI Feedback Loop (operator corrections → better drafts).
--
-- Today the pipeline writes the AI draft into email_ticket.draft_reply, and when the operator
-- edits and sends, that same column is overwritten with the edited text — the original AI draft
-- is lost, so the pair "what the AI wrote → what the operator actually sent" cannot be captured.
--
-- This phase preserves the AI's original draft and records every meaningful operator correction
-- as a training_example. Session 2 retrieves the most semantically similar past corrections (per
-- tenant + ticket type) and injects them into the drafting prompt as few-shot examples, so drafts
-- improve the longer a client uses the system — the Levity-style retention loop. Retrieval uses
-- pgvector cosine similarity over embeddings produced locally by Ollama (nomic-embed-text), so the
-- corrections never leave the UK server (consistent with the local-LLM / GDPR positioning).

-- pgvector lives in the pgvector/pgvector:pg16 image (docker-compose) and the test container.
-- Flyway runs as the admin role (spring.flyway.*), which has the privilege to create extensions.
CREATE EXTENSION IF NOT EXISTS vector;

-- The unmodified AI-generated draft, captured by the pipeline alongside draft_reply and never
-- touched by operator edits. Nullable: pre-3.1 tickets and proactive tickets have none.
ALTER TABLE email_ticket ADD COLUMN ai_original_draft TEXT;

-- One captured correction. enquiry_text is the exact input the AI saw (email body + parsed
-- attachment text); ai_draft is what it produced; operator_final is what the operator actually
-- sent. embedding is the nomic-embed-text vector of enquiry_text (768 dims) — nullable so a
-- correction is never lost when the embedding model is unavailable (best-effort, backfillable).
CREATE TABLE training_example (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL REFERENCES tenant(id),
    -- Source ticket for traceability; no FK so soft-deleting/purging the ticket leaves the
    -- learned example intact (the correction's value outlives the ticket).
    source_ticket_id BIGINT,
    -- TicketType enum name (RFQ, STATUS_UPDATE, …) as text — the pre-filter dimension for
    -- retrieval (a document-request correction must not inform an RFQ draft).
    ticket_type     VARCHAR(50),
    enquiry_text    TEXT         NOT NULL,
    ai_draft        TEXT         NOT NULL,
    operator_final  TEXT         NOT NULL,
    embedding       vector(768),
    created_at      TIMESTAMP    NOT NULL DEFAULT now()
);

-- Same two-layer isolation as the V19/V20/V21/V22/V26 tables: RLS guards native SQL / raw JDBC,
-- Hibernate @TenantId guards entity queries. Grants arrive automatically via the
-- ALTER DEFAULT PRIVILEGES from V19 (this migration runs as the admin role).
ALTER TABLE training_example ENABLE ROW LEVEL SECURITY;
ALTER TABLE training_example FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON training_example
    USING (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    )
    WITH CHECK (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    );

-- Pre-filter index (tenant + type) for the structured scope of retrieval; the HNSW index ranks
-- by cosine distance within it. HNSW handles incremental inserts well and needs no training step,
-- unlike IVFFlat — the right default for a table that grows one correction at a time.
CREATE INDEX idx_training_example_scope ON training_example (tenant_id, ticket_type);
CREATE INDEX idx_training_example_embedding ON training_example
    USING hnsw (embedding vector_cosine_ops);

-- feature.ai-feedback-loop: ON by default. Capturing corrections and (Session 2) injecting
-- examples never emails a customer unsupervised — every draft is still operator-reviewed — so it
-- follows the analytics / inbox-query / document-request precedent (ON), not auto-send's (OFF).
INSERT INTO app_setting (key, value) VALUES
    ('feature.ai-feedback-loop', 'true')
ON CONFLICT (key) DO NOTHING;
