-- Phase 4.8 demo/test-data harness: road-haulage job references for the second demo
-- tenant ("Midlands Haulage & Logistics Ltd"). port-service is a simulated client TMS
-- with no tenant concept, so these live alongside the V3 sea containers and are looked
-- up by the same ItemLookupService. The identifier format (MID-YYYY-NNNN) is deliberately
-- different from ISO 6346 — it exercises the inbox's per-tenant extraction-fallback-regex.
--
-- Statuses reuse the ContainerStatus enum (chk_container_status): for a road job
-- AT_PORT ~ at the ferry terminal, CUSTOMS ~ at the border post.
INSERT INTO container (container_number, status, eta, location, updated_at) VALUES
    ('MID-2026-0481', 'IN_TRANSIT', '2026-05-02 18:00:00', 'Eddie Stobart unit EU-4471, M20 toward Dover, ETA Rotterdam', now()),
    ('MID-2026-0492', 'AT_PORT',    '2026-04-22 09:00:00', 'DFDS ferry terminal, Dover Eastern Docks, Berth 7',          now()),
    ('MID-2026-0508', 'CUSTOMS',    '2026-04-21 13:30:00', 'Calais border inspection post, lane 3',                      now()),
    ('MID-2026-0515', 'RELEASED',   '2026-04-20 08:00:00', 'Cleared at Coventry depot, awaiting tip slot',               now()),
    ('MID-2026-0523', 'DELIVERED',  '2026-04-18 11:00:00', 'Delivered — Jaguar Land Rover, Solihull, Gate 2',            now())
ON CONFLICT (container_number) DO NOTHING;
