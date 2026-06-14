-- Phase 2.7: Document Request Handling.
--
-- Today a DOCUMENT_REQUEST ticket (customer asks for a POD, bill of lading, customs paper, …)
-- flows through the generic pipeline and gets a generic text reply — no document, no operator
-- signal that one is owed. This phase adds a per-tenant document store: operators upload files
-- keyed by the shipment identifier, the pipeline detects which document is requested, looks it
-- up, and either attaches it to the reply (through the existing auto-send gate) or escalates to
-- the operator ("document X needed for shipment Y — not on file").
--
-- The store lives inbox-side (not in port-service, which stays a dumb lookup backend) — the same
-- choice as rate sheets: this is operator/tenant-owned content, not TMS data.

CREATE TABLE tenant_document (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant(id),
    -- Shipment reference the document belongs to (e.g. an ISO 6346 container number) — matched
    -- against the identifier the pipeline extracts from the customer enquiry.
    identifier    VARCHAR(255) NOT NULL,
    -- DocumentType enum name (POD, BILL_OF_LADING, CUSTOMS, …) stored as text so adding a type
    -- never corrupts existing rows.
    doc_type      VARCHAR(50)  NOT NULL,
    filename      VARCHAR(512) NOT NULL,
    content_type  VARCHAR(255),
    -- File bytes stored inline. Adequate for the typical freight document (a few hundred KB PDF);
    -- object storage is a Phase 4 concern, not pre-client infrastructure.
    content       BYTEA        NOT NULL,
    file_size     BIGINT       NOT NULL,
    uploaded_by   VARCHAR(255),
    uploaded_at   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_tenant_document_lookup ON tenant_document (tenant_id, identifier);

-- Same two-layer isolation as the V19/V20/V21/V22 tables: RLS guards native SQL / raw JDBC,
-- Hibernate @TenantId guards entity queries. Grants arrive automatically via the
-- ALTER DEFAULT PRIVILEGES from V19 (this migration runs as the admin role).
ALTER TABLE tenant_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_document FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_document
    USING (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    )
    WITH CHECK (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    );

-- Pipeline annotations on the ticket (Phase 2.7), both nullable:
--   requested_doc_type   — which document the AI determined the customer is asking for; shown in
--                          the operator UI for every DOCUMENT_REQUEST ticket, even when no file
--                          is on hand (so the operator knows what to provide).
--   attached_document_id — the tenant_document resolved for this reply; EmailReplyService loads
--                          it and attaches the bytes on send (both manual and auto-send paths).
ALTER TABLE email_ticket ADD COLUMN requested_doc_type   VARCHAR(50);
ALTER TABLE email_ticket ADD COLUMN attached_document_id BIGINT REFERENCES tenant_document(id);

-- feature.document-request: unlike auto-send / proactive automation, this never emails a customer
-- unsupervised — DOCUMENT_REQUEST is not in the autosend.types allowlist by default, so a reply is
-- always operator-reviewed. It therefore follows the analytics / inbox-query precedent (ON), not
-- the auto-send precedent (OFF). Seeded as the platform baseline; a tenant can override on /settings.
-- ai.document-detect-prompt: routes the enquiry to ONE DocumentType name (the AI "what is asked").
-- ai.document-cover-prompt:  drafts the reply — {email}, {documentType}, {itemContext} and
--                            {documentStatus} (attached vs. not on file) substituted at runtime.
INSERT INTO app_setting (key, value) VALUES
    ('feature.document-request', 'true'),
    ('ai.document-detect-prompt',
'You are a classifier for a freight logistics company. The customer email below asks for one or more
documents. Decide which SINGLE document type they are primarily requesting and answer with EXACTLY
ONE of these names, nothing else:

POD - proof of delivery / delivery note
BILL_OF_LADING - bill of lading / B/L / sea waybill
CUSTOMS - customs paperwork, entry documents, C88, T1, clearance papers
INVOICE_COPY - a copy of an invoice or freight charges
PACKING_LIST - packing list / manifest of goods
CMR - CMR consignment note / road transport note
OTHER - anything else, or unclear

Customer email:
{email}

Answer with one type name only.'),
    ('ai.document-cover-prompt',
'You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
The customer has requested a document. Write a concise, professional email reply.
Document requested: {documentType}
{documentStatus}
Use only the information provided. Be helpful, polite and factual. Do NOT invent information.

Shipment information:
{itemContext}

Customer''s email:
{email}

Write only the email reply body (no subject line).')
ON CONFLICT (key) DO NOTHING;
