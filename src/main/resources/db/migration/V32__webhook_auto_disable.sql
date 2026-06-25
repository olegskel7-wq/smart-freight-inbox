-- Phase 3.5 S2: auto-disable a dead webhook endpoint after a run of fully-failed deliveries.
--
-- A delivery only reaches DEAD after exhausting its whole retry budget (8 attempts with exponential
-- backoff, see WebhookDispatcher), so a DEAD delivery is already strong evidence the endpoint is gone.
-- consecutive_failures counts DEAD deliveries in a row per subscription; any DELIVERED resets it to 0.
-- When it crosses webhook.auto-disable-after the dispatcher flips enabled=false and records why, so the
-- UI can show "auto-disabled" distinctly from an operator switching it off. Re-enabling clears both.
--
-- No new RLS policy needed: these are columns on webhook_subscription, already guarded by the V30
-- tenant_isolation policy. The dispatcher updates them under the system context (app.is_system = true).
ALTER TABLE webhook_subscription
    ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0,
    ADD COLUMN disabled_reason      VARCHAR(255);
