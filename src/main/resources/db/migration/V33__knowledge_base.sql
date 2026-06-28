-- Phase 3.9: Knowledge-base deflection (RAG over the tenant's own knowledge base).
--
-- Today a substantive reply only exists where the pipeline has structured data (a backend item or a
-- matched rate). Typical "how / where / when" enquiries (GENERAL and other non-RFQ types) have no such
-- data, so they fall through to a generic draft. This phase lets an operator store free-text knowledge
-- (FAQ / policies / quoting rules) per tenant, embeds each document locally with nomic-embed-text (the
-- same Ollama stack as Phase 3.1), and at draft time retrieves the top-k most similar documents by
-- pgvector cosine to ground the reply via a new {knowledgeContext} placeholder. This is what Intercom
-- Fin sells (autonomous resolution from a knowledge base) but local and GDPR-safe — the knowledge never
-- leaves the UK server. It generalises across every vertical (estate agents / legal / NHS), where such
-- "how do I…" enquiries are the bulk of inbound mail.
--
-- Sibling of V28 (training_example): same local-embedding + native-::vector-upsert + cosine-search
-- pattern. The vector extension was already created by V28, so it is not re-created here.

-- One stored knowledge document. content is the operator-authored text (FAQ answer, policy, rule);
-- source records where it came from ('manual' in S1; an uploaded file name once S2 adds parsing).
-- embedding is the nomic-embed-text vector of title + content (768 dims) — nullable so a document is
-- never lost when the embedding model is unavailable (best-effort, backfillable), exactly like V28.
CREATE TABLE knowledge_doc (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenant(id),
    title       VARCHAR(255) NOT NULL,
    content     TEXT         NOT NULL,
    -- Provenance: 'manual' for operator text entry, or the uploaded file name (S2 file-upload).
    source      VARCHAR(255),
    embedding   vector(768),
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- Same two-layer isolation as the V19/V20/V21/V22/V26/V28 tables: RLS guards native SQL / raw JDBC,
-- Hibernate @TenantId guards entity queries. Grants arrive automatically via the
-- ALTER DEFAULT PRIVILEGES from V19 (this migration runs as the admin role).
ALTER TABLE knowledge_doc ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_doc FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON knowledge_doc
    USING (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    )
    WITH CHECK (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    );

-- HNSW cosine index over the embedding — handles incremental inserts well and needs no training
-- step (unlike IVFFlat), the right default for a table that grows one document at a time. Retrieval
-- is type-agnostic (knowledge is not pre-filtered by ticket type), so RLS is the only scope.
CREATE INDEX idx_knowledge_doc_embedding ON knowledge_doc
    USING hnsw (embedding vector_cosine_ops);

-- feature.knowledge-base: ON by default. Grounding a draft with knowledge never emails a customer
-- unsupervised — every draft is still operator-reviewed — so it follows the analytics / feedback-loop /
-- document-request precedent (ON), not auto-send's (OFF).
INSERT INTO app_setting (key, value) VALUES
    ('feature.knowledge-base', 'true')
ON CONFLICT (key) DO NOTHING;
