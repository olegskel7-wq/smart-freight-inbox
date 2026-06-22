-- Phase 3.5 S1: outbound webhook API (push domain events to a tenant's CRM / Zapier / Make).
--
-- CRM-agnostic by design: a tenant registers an HTTPS endpoint + a shared secret and subscribes to
-- event types; SmartFreight POSTs a signed JSON envelope when a matching event happens. No per-CRM
-- connector — Salesforce/HubSpot are reached through their generic catch-hooks (or Zapier/Make).
--
-- Delivery reuses the transactional-outbox discipline already proven in outbox_event (V14): a
-- webhook_delivery row is INSERTed in the SAME transaction as the business change that produced the
-- event, so "ticket created" and "delivery queued" are atomic. WebhookDispatcher then polls due rows
-- (FOR UPDATE SKIP LOCKED, multi-instance safe), POSTs them with retry/backoff, and DB stays the
-- single source of truth for delivery state (visible in the UI log) — the network is just transport.

-- A tenant's subscription: where to deliver, which events, the HMAC secret (AES-GCM at rest, reusing
-- SecretCipher like tenant_mailbox.password_enc). event_types is a CSV of WebhookEventType names, or
-- '*' for all. Disabling stops NEW events being queued; already-queued deliveries still drain.
CREATE TABLE webhook_subscription (
    id          BIGSERIAL     PRIMARY KEY,
    tenant_id   BIGINT        NOT NULL REFERENCES tenant(id),
    url         VARCHAR(2048) NOT NULL,
    secret_enc  VARCHAR(512)  NOT NULL,
    event_types VARCHAR(512)  NOT NULL DEFAULT '*',
    description VARCHAR(255),
    enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT now()
);

-- One row per (event, subscription). payload is the immutable signed envelope; the HMAC signature is
-- computed per HTTP attempt (it includes a fresh timestamp), so it is not stored. status walks
-- PENDING -> DELIVERED, or PENDING -> DEAD after the attempt budget is exhausted. next_attempt_at
-- drives the backoff: a failed attempt pushes it into the future, the dispatcher only picks up due rows.
-- event_id is a stable UUID shared by every delivery of the same logical event — the consumer dedupes on it.
CREATE TABLE webhook_delivery (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL REFERENCES tenant(id),
    subscription_id BIGINT       NOT NULL REFERENCES webhook_subscription(id) ON DELETE CASCADE,
    event_id        UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP    NOT NULL DEFAULT now(),
    response_status INT,
    last_error      VARCHAR(1000),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMP
);

-- The dispatcher's hot query is "due PENDING rows": a partial index keeps it O(backlog), not O(history).
CREATE INDEX idx_webhook_delivery_due ON webhook_delivery (next_attempt_at) WHERE status = 'PENDING';
-- The UI delivery log lists a subscription's recent attempts newest-first.
CREATE INDEX idx_webhook_delivery_subscription ON webhook_delivery (subscription_id, id DESC);

-- Same two-layer isolation as every tenant table since V19: Postgres RLS guards native SQL / the
-- system-context dispatcher, Hibernate @TenantId guards entity queries. Grants arrive via the
-- ALTER DEFAULT PRIVILEGES set in V19 (this migration runs as the admin role).
ALTER TABLE webhook_subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_subscription FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON webhook_subscription
    USING (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    )
    WITH CHECK (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    );

ALTER TABLE webhook_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_delivery FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON webhook_delivery
    USING (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    )
    WITH CHECK (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    );
