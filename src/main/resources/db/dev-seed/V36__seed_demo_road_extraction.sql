-- Phase 4.8 S2 demo: per-tenant identifier extraction for the road-haulage demo tenant (90001).
-- Found by the mail-harness golden set: Midlands' job refs (MID-2026-0481, port-service V7) are not
-- ISO 6346 container numbers, so the platform-default extraction prompt + fallback regex returned
-- nothing -> no tracking lookup, confidence 0.3 on every status/POD enquiry. The per-tenant override
-- chain (tenant_setting -> app_setting -> code default) exists exactly for a second vertical.
--
-- Dev-seed only (dev profile) - the prod docker backend does not apply it, load live via psql.
-- Idempotent: upsert on the (tenant_id, key) unique constraint.
INSERT INTO tenant_setting (tenant_id, key, value)
VALUES
(90001, 'ai.extraction-prompt',
 E'You are a road haulage assistant. Extract the haulage job reference from the email below.\n'
 || E'Job references look like MID-2026-0481: the letters MID, a 4-digit year and a 4-digit number, separated by hyphens.\n'
 || E'Return ONLY the job reference, nothing else. If no job reference is found, return "NOT_FOUND".\n\n'
 || E'Email:\n{email}'),
(90001, 'ai.extraction-fallback-regex', E'\\b(MID-\\d{4}-\\d{4})\\b')
ON CONFLICT (tenant_id, key) DO UPDATE SET value = EXCLUDED.value;
