-- =============================================================================
-- Phase 4.8 — Demo / test-data harness (S1: direct dev-seed).
-- Runs ONLY under the 'dev' Spring profile (application-dev.yaml adds db/dev-seed
-- to the Flyway locations). NEVER runs in production.
--
-- Flyway runs migrations with the admin credentials, which bypass RLS, so the
-- explicit-tenant_id inserts below land in the right tenant without juggling the
-- app.tenant_id GUC (same as the existing dev seeds).
--
-- A fictional but UK-credible dataset for two tenants in different verticals — the
-- main test of cross-tenant RLS isolation:
--   * tenant 1  ("Felixstowe Freight Solutions Ltd") — sea-freight forwarder. This is
--     the tenant the seeded Keycloak operator (operator@smartfreight.com, tenant_id=1)
--     belongs to, so logging in shows this inbox immediately.
--   * tenant 90001 ("Midlands Haulage & Logistics Ltd") — road haulage. High id so it
--     never collides with tenants created through /admin/tenants (sequence-based, small).
--     To browse it in the UI, provision a Keycloak operator with tenant_id=90001.
--
-- Identifiers cross-reference the port-service demo data:
--   * tenant 1 reuses the V3 sea containers (MSCU1234567, HLXU8764321, …).
--   * tenant 90001 uses the V7 road job-refs (MID-2026-0481, …).
-- All senders are @example.com so nothing can ever be mistaken for a real customer.
--
-- Explicit high ids (tickets 9000xx, documents 900xx) keep these rows clear of any
-- real data; the sequences are deliberately NOT advanced, so live inserts keep using
-- small ids and never collide.
-- =============================================================================

-- --- Tenants -----------------------------------------------------------------
-- Rename the Default tenant for the demo (dev-only cosmetic — the existing operator
-- already maps to tenant_id=1).
UPDATE tenant SET name = 'Felixstowe Freight Solutions Ltd' WHERE id = 1;

INSERT INTO tenant (id, name) VALUES (90001, 'Midlands Haulage & Logistics Ltd')
    ON CONFLICT (id) DO NOTHING;

-- --- Carrier rates -----------------------------------------------------------
-- Tenant 1 — sea routes (per container). One row is deliberately expired.
INSERT INTO carrier_rate (tenant_id, carrier, origin, destination, rate, currency, margin_percent, valid_until) VALUES
    (1, 'Maersk',      'Shanghai',  'Felixstowe',  2400.00, 'GBP', 12.0, CURRENT_DATE + 60),
    (1, 'MSC',         'Ningbo',    'Felixstowe',  2250.00, 'GBP', 10.0, CURRENT_DATE + 45),
    (1, 'Hapag-Lloyd', 'Rotterdam', 'Felixstowe',   850.00, 'GBP',  8.0, CURRENT_DATE + 90),
    (1, 'COSCO',       'Shanghai',  'Southampton', 2500.00, 'GBP', 12.0, CURRENT_DATE - 5)    -- expired
ON CONFLICT (tenant_id, lower(carrier), lower(origin), lower(destination)) DO NOTHING;

-- Tenant 90001 — road routes (per FTL). One row is deliberately expired.
INSERT INTO carrier_rate (tenant_id, carrier, origin, destination, rate, currency, margin_percent, valid_until) VALUES
    (90001, 'DFDS',        'Birmingham', 'Rotterdam', 1250.00, 'GBP', 10.0, CURRENT_DATE + 50),
    (90001, 'Ferrymasters','Birmingham', 'Hamburg',   1680.00, 'GBP', 11.0, CURRENT_DATE + 40),
    (90001, 'Stobart',     'Birmingham', 'Paris',     1450.00, 'GBP', 12.0, CURRENT_DATE + 70),
    (90001, 'P&O',         'Birmingham', 'Dublin',     980.00, 'GBP',  9.0, CURRENT_DATE - 10)   -- expired
ON CONFLICT (tenant_id, lower(carrier), lower(origin), lower(destination)) DO NOTHING;

-- --- Tenant documents --------------------------------------------------------
-- Placeholder byte payloads (UTF-8 text marked application/pdf) so the attach/escalate
-- flow and the /documents download work end to end. Swap for real PDFs via the UI if a
-- pixel-perfect demo document is needed.
INSERT INTO tenant_document (id, tenant_id, identifier, doc_type, filename, content_type, content, file_size, uploaded_by) VALUES
    (90001, 1, 'OOLU9876543', 'BILL_OF_LADING', 'BoL-OOLU9876543.pdf', 'application/pdf',
        convert_to('DEMO BILL OF LADING — OOLU9876543 — Felixstowe Freight Solutions Ltd', 'UTF8'),
        length('DEMO BILL OF LADING — OOLU9876543 — Felixstowe Freight Solutions Ltd'), 'operator@smartfreight.com'),
    (90002, 1, 'TRHU9182736', 'POD', 'POD-TRHU9182736.pdf', 'application/pdf',
        convert_to('DEMO PROOF OF DELIVERY — TRHU9182736 — signed Dartford', 'UTF8'),
        length('DEMO PROOF OF DELIVERY — TRHU9182736 — signed Dartford'), 'operator@smartfreight.com'),
    (90003, 1, 'HLXU8764321', 'INVOICE_COPY', 'INV-HLXU8764321.pdf', 'application/pdf',
        convert_to('DEMO INVOICE COPY — HLXU8764321 — storage & handling', 'UTF8'),
        length('DEMO INVOICE COPY — HLXU8764321 — storage & handling'), 'operator@smartfreight.com'),
    (90004, 1, 'CMAU5551234', 'CUSTOMS', 'CustomsC88-CMAU5551234.pdf', 'application/pdf',
        convert_to('DEMO CUSTOMS C88 — CMAU5551234 — HMRC entry', 'UTF8'),
        length('DEMO CUSTOMS C88 — CMAU5551234 — HMRC entry'), 'operator@smartfreight.com'),
    -- Tenant 90001
    (90005, 90001, 'MID-2026-0523', 'POD', 'POD-MID-2026-0523.pdf', 'application/pdf',
        convert_to('DEMO PROOF OF DELIVERY — MID-2026-0523 — signed JLR Solihull', 'UTF8'),
        length('DEMO PROOF OF DELIVERY — MID-2026-0523 — signed JLR Solihull'), 'operator@smartfreight.com'),
    (90006, 90001, 'MID-2026-0515', 'CMR', 'CMR-MID-2026-0515.pdf', 'application/pdf',
        convert_to('DEMO CMR CONSIGNMENT NOTE — MID-2026-0515', 'UTF8'),
        length('DEMO CMR CONSIGNMENT NOTE — MID-2026-0515'), 'operator@smartfreight.com')
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- Email tickets — golden set. Explicit column list; defaults rely on the schema
-- for retry_count/deleted/version/sla_breached/proactive where omitted.
-- =============================================================================

-- --- Tenant 1 (Felixstowe Freight Solutions Ltd) -----------------------------
INSERT INTO email_ticket
    (id, tenant_id, message_id, sender_email, subject, body, extracted_identifier, attachment_text,
     status, ticket_type, draft_reply, ai_original_draft, ai_confidence,
     sla_deadline_at, sla_breached, proactive, requested_doc_type, attached_document_id,
     created_at, updated_at, version)
VALUES
-- RFQ, fresh, rate-matched, with a parsed Excel quote request attachment
(900001, 1, '<demo-900001@globaltrade.example.com>', 'rachel.green@globaltrade.example.com',
 'Quote request: 2 x 40HC Shanghai to Felixstowe',
 E'Hi,\n\nPlease quote for 2 x 40HC from Shanghai to Felixstowe, ready 1st May.\nSee attached spec.\n\nThanks,\nRachel Green\nGlobalTrade Imports',
 NULL, E'Quote Request\nOrigin: Shanghai\nDestination: Felixstowe\nContainers: 2 x 40HC\nCommodity: homeware\nReady date: 2026-05-01',
 'DRAFT_READY', 'RFQ',
 E'Dear Ms Green,\n\nThank you for your enquiry. For 2 x 40HC Shanghai to Felixstowe our rate is GBP 2,688 per container (all-in), valid 30 days. Transit approx 32 days.\n\nKind regards,\nFelixstowe Freight Solutions',
 NULL, 0.7,
 NOW() + INTERVAL '90 minutes', false, false, NULL, NULL,
 NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '25 minutes', 1),

-- STATUS_UPDATE auto-sent, draft accepted unchanged (acceptance KPI = accepted)
(900002, 1, '<demo-900002@logistics-eu.example.com>', 'anna.schmidt@logistics-eu.example.com',
 'Where is HLXU8764321?',
 E'Hello, can you confirm the status of container HLXU8764321 at Felixstowe?\n\nAnna Schmidt',
 'HLXU8764321', NULL,
 'AUTO_SENT', 'STATUS_UPDATE',
 E'Dear Ms Schmidt,\n\nContainer HLXU8764321 is AT PORT at Felixstowe Terminal 2, Row D, Berth 9, available for collection.\n\nKind regards,\nFelixstowe Freight Solutions',
 E'Dear Ms Schmidt,\n\nContainer HLXU8764321 is AT PORT at Felixstowe Terminal 2, Row D, Berth 9, available for collection.\n\nKind regards,\nFelixstowe Freight Solutions',
 0.92,
 NULL, false, false, NULL, NULL,
 NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day' + INTERVAL '3 minutes', 2),

-- STATUS_UPDATE, SLA breached, awaiting operator
(900003, 1, '<demo-900003@tradelink.example.com>', 'peter.jones@tradelink.example.com',
 'CMAU5551234 customs status — urgent',
 E'Container CMAU5551234 should have cleared customs by now. Please advise.\n\nPeter Jones',
 'CMAU5551234', NULL,
 'DRAFT_READY', 'STATUS_UPDATE',
 E'Dear Mr Jones,\n\nContainer CMAU5551234 is currently under HMRC examination (Bay 3, Felixstowe). We are chasing clearance and will update you as soon as it is released.\n\nKind regards,\nFelixstowe Freight Solutions',
 NULL, 0.6,
 NOW() - INTERVAL '3 hours', true, false, NULL, NULL,
 NOW() - INTERVAL '7 hours', NOW() - INTERVAL '6 hours', 1),

-- DOCUMENT_REQUEST, BoL on file → attached
(900004, 1, '<demo-900004@globalship.example.com>', 'maria.kowalski@globalship.example.com',
 'Need Bill of Lading for OOLU9876543',
 E'Good morning, please send the Bill of Lading for container OOLU9876543 for our records.\n\nMaria Kowalski',
 'OOLU9876543', NULL,
 'DRAFT_READY', 'DOCUMENT_REQUEST',
 E'Dear Ms Kowalski,\n\nPlease find attached the Bill of Lading for container OOLU9876543. Let us know if you need anything further.\n\nKind regards,\nFelixstowe Freight Solutions',
 NULL, 0.82,
 NOW() + INTERVAL '6 hours', false, false, 'BILL_OF_LADING', 90001,
 NOW() - INTERVAL '4 hours', NOW() - INTERVAL '3 hours', 1),

-- DOCUMENT_REQUEST, nothing on file → escalated (no attachment)
(900005, 1, '<demo-900005@balt-cargo.example.com>', 'rain.tamm@balt-cargo.example.com',
 'Proof of delivery for EVRU3219876',
 E'We urgently need a POD for container EVRU3219876. Our customer is disputing receipt.\n\nRain Tamm',
 'EVRU3219876', NULL,
 'DRAFT_READY', 'DOCUMENT_REQUEST',
 E'Dear Mr Tamm,\n\nThank you for your request. We do not currently hold a proof of delivery on file for EVRU3219876 (still in transit). We have escalated this to our operations team and will follow up shortly.\n\nKind regards,\nFelixstowe Freight Solutions',
 NULL, 0.5,
 NOW() + INTERVAL '5 hours', false, false, 'POD', NULL,
 NOW() - INTERVAL '5 hours', NOW() - INTERVAL '4 hours', 1),

-- BOOKING_CONFIRM from GlobalTrade — converts the earlier quote (900010). Operator edited.
(900006, 1, '<demo-900006@globaltrade.example.com>', 'rachel.green@globaltrade.example.com',
 'Re: Quote — go ahead with the booking',
 E'Thanks for the quote, please go ahead and book the 2 x 40HC Shanghai to Felixstowe.\n\nRachel Green\nGlobalTrade Imports',
 NULL, NULL,
 'CLOSED', 'BOOKING_CONFIRM',
 E'Dear Ms Green,\n\nThank you — your booking for 2 x 40HC Shanghai to Felixstowe is confirmed. Our bookings desk will send the booking reference and cut-off details shortly.\n\nKind regards,\nFelixstowe Freight Solutions',
 E'Dear Ms Green,\n\nThank you for confirming. Your booking is being processed and we will send the details shortly.\n\nKind regards,\nFelixstowe Freight Solutions',
 0.7,
 NULL, false, false, NULL, NULL,
 NOW() - INTERVAL '3 days', NOW() - INTERVAL '3 days' + INTERVAL '40 minutes', 3),

-- INVOICE_QUERY
(900007, 1, '<demo-900007@oceanline.example.com>', 'ingrid.larsen@oceanline.example.com',
 'Demurrage charge query CMAU5551234',
 E'We received a demurrage invoice for CMAU5551234. Can you explain the free-time calculation?\n\nIngrid Larsen',
 'CMAU5551234', NULL,
 'DRAFT_READY', 'INVOICE_QUERY',
 E'Dear Ms Larsen,\n\nThank you for your query. Free time on CMAU5551234 expired on the date shown; demurrage accrues per day thereafter. We will send the detailed breakdown from our accounts team.\n\nKind regards,\nFelixstowe Freight Solutions',
 NULL, 0.45,
 NOW() + INTERVAL '7 hours', false, false, NULL, NULL,
 NOW() - INTERVAL '20 hours', NOW() - INTERVAL '19 hours', 1),

-- GENERAL
(900008, 1, '<demo-900008@newcustomer.example.com>', 'david.owen@newcustomer.example.com',
 'Do you handle reefer cargo?',
 E'Hello, we are a food importer — do you handle temperature-controlled (reefer) containers into Felixstowe?\n\nDavid Owen',
 NULL, NULL,
 'DRAFT_READY', 'GENERAL',
 E'Dear Mr Owen,\n\nYes, we handle reefer cargo into Felixstowe and Southampton, including pre-arrival plug bookings. Please share your route and volumes and we will prepare a quote.\n\nKind regards,\nFelixstowe Freight Solutions',
 NULL, 0.3,
 NOW() + INTERVAL '8 hours', false, false, NULL, NULL,
 NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days' + INTERVAL '1 hour', 1),

-- IRRELEVANT (spam — no draft, terminal)
(900009, 1, '<demo-900009@marketing.example.com>', 'promo@marketing.example.com',
 'Boost your logistics with our SEO services!!!',
 E'Dear Sir/Madam, we offer guaranteed first-page Google rankings for freight companies. Reply to claim your free audit!',
 NULL, NULL,
 'IRRELEVANT', NULL,
 NULL, NULL, NULL,
 NULL, false, false, NULL, NULL,
 NOW() - INTERVAL '6 days', NOW() - INTERVAL '6 days', 1),

-- RFQ quote SENT to GlobalTrade — Q1, CONVERTED by 900006
(900010, 1, '<demo-900010@globaltrade.example.com>', 'rachel.green@globaltrade.example.com',
 'Pricing for Shanghai-Felixstowe shipment',
 E'Could you price a 40HC Shanghai to Felixstowe for end of April?\n\nRachel Green',
 NULL, NULL,
 'CLOSED', 'RFQ',
 E'Dear Ms Green,\n\nOur rate for a 40HC Shanghai to Felixstowe is GBP 2,688 all-in, valid 30 days. Transit approx 32 days. We would be glad to book this for you.\n\nKind regards,\nFelixstowe Freight Solutions',
 E'Dear Ms Green,\n\nOur rate for a 40HC Shanghai to Felixstowe is GBP 2,688 all-in, valid 30 days. Transit approx 32 days. We would be glad to book this for you.\n\nKind regards,\nFelixstowe Freight Solutions',
 0.72,
 NULL, false, false, NULL, NULL,
 NOW() - INTERVAL '6 days', NOW() - INTERVAL '6 days' + INTERVAL '50 minutes', 2),

-- RFQ quote SENT to HarbourLine — Q2, SILENT (gets a follow-up nudge 900012)
(900011, 1, '<demo-900011@harbourline.example.com>', 'tom.fisher@harbourline.example.com',
 'Rate for Rotterdam-Felixstowe groupage',
 E'Please quote LCL Rotterdam to Felixstowe, approx 6 CBM.\n\nTom Fisher\nHarbourLine',
 NULL, NULL,
 'CLOSED', 'RFQ',
 E'Dear Mr Fisher,\n\nThank you for your enquiry. For 6 CBM LCL Rotterdam to Felixstowe our rate is GBP 540 all-in, valid 30 days. We look forward to your booking.\n\nKind regards,\nFelixstowe Freight Solutions',
 E'Dear Mr Fisher,\n\nThank you for your enquiry. For 6 CBM LCL Rotterdam to Felixstowe our rate is GBP 540 all-in, valid 30 days. We look forward to your booking.\n\nKind regards,\nFelixstowe Freight Solutions',
 0.71,
 NULL, false, false, NULL, NULL,
 NOW() - INTERVAL '4 days', NOW() - INTERVAL '4 days' + INTERVAL '35 minutes', 2),

-- Proactive quote follow-up nudge for the silent Q2 (HarbourLine)
(900012, 1, '<demo-900012-followup@felixstowefreight.co.uk>', 'tom.fisher@harbourline.example.com',
 'Following up: Rotterdam-Felixstowe groupage',
 E'(proactive follow-up generated for the quote sent on the original enquiry)',
 NULL, NULL,
 'DRAFT_READY', 'RFQ',
 E'Dear Mr Fisher,\n\nJust following up on the LCL quote we sent for Rotterdam to Felixstowe. The rate is still available — would you like us to book it in?\n\nKind regards,\nFelixstowe Freight Solutions',
 NULL, 0.6,
 NULL, false, true, NULL, NULL,
 NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours', 1),

-- Proactive STATUS_UPDATE milestone (TEMU4448888 RELEASED), auto-sent
(900013, 1, '<demo-900013-proactive@felixstowefreight.co.uk>', 'sara.ferry@ferryside.example.com',
 'Update: your shipment TEMU4448888 has been released',
 E'(proactive milestone notification)',
 'TEMU4448888', NULL,
 'AUTO_SENT', 'STATUS_UPDATE',
 E'Dear Sara,\n\nGood news — container TEMU4448888 has been RELEASED at Felixstowe Terminal 1, Row B, Gate 3, and is ready for haulier collection.\n\nKind regards,\nFelixstowe Freight Solutions',
 E'Dear Sara,\n\nGood news — container TEMU4448888 has been RELEASED at Felixstowe Terminal 1, Row B, Gate 3, and is ready for haulier collection.\n\nKind regards,\nFelixstowe Freight Solutions',
 0.9,
 NULL, false, true, NULL, NULL,
 NOW() - INTERVAL '12 hours', NOW() - INTERVAL '12 hours' + INTERVAL '2 minutes', 2)
ON CONFLICT (id) DO NOTHING;

-- --- Tenant 90001 (Midlands Haulage & Logistics Ltd) -------------------------
INSERT INTO email_ticket
    (id, tenant_id, message_id, sender_email, subject, body, extracted_identifier, attachment_text,
     status, ticket_type, draft_reply, ai_original_draft, ai_confidence,
     sla_deadline_at, sla_breached, proactive, requested_doc_type, attached_document_id,
     created_at, updated_at, version)
VALUES
-- BOOKING_CONFIRM, rate-matched road route
(900020, 90001, '<demo-900020@acmeparts.example.com>', 'sue.clark@acmeparts.example.com',
 'Book a full load Birmingham to Rotterdam',
 E'Please book one FTL Birmingham to Rotterdam for collection Thursday.\n\nSue Clark\nAcme Parts',
 NULL, NULL,
 'DRAFT_READY', 'BOOKING_CONFIRM',
 E'Dear Ms Clark,\n\nThank you — we can book one FTL Birmingham to Rotterdam at GBP 1,375 (DFDS, Thursday collection). Please confirm the collection address and we will arrange the driver.\n\nKind regards,\nMidlands Haulage & Logistics',
 NULL, 0.7,
 NOW() + INTERVAL '25 minutes', false, false, NULL, NULL,
 NOW() - INTERVAL '90 minutes', NOW() - INTERVAL '80 minutes', 1),

-- STATUS_UPDATE auto-sent, accepted (job at Dover)
(900021, 90001, '<demo-900021@acmeparts.example.com>', 'sue.clark@acmeparts.example.com',
 'Where is job MID-2026-0492?',
 E'Hi, where is our load MID-2026-0492? Customer is asking.\n\nSue Clark',
 'MID-2026-0492', NULL,
 'AUTO_SENT', 'STATUS_UPDATE',
 E'Dear Ms Clark,\n\nJob MID-2026-0492 is at the DFDS ferry terminal, Dover Eastern Docks (Berth 7), awaiting the next sailing.\n\nKind regards,\nMidlands Haulage & Logistics',
 E'Dear Ms Clark,\n\nJob MID-2026-0492 is at the DFDS ferry terminal, Dover Eastern Docks (Berth 7), awaiting the next sailing.\n\nKind regards,\nMidlands Haulage & Logistics',
 0.9,
 NULL, false, false, NULL, NULL,
 NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days' + INTERVAL '2 minutes', 2),

-- STATUS_UPDATE at customs, awaiting operator
(900022, 90001, '<demo-900022@brightlogistics.example.com>', 'mark.evans@brightlogistics.example.com',
 'MID-2026-0508 stuck at Calais?',
 E'Our load MID-2026-0508 seems held at the border. Any news?\n\nMark Evans',
 'MID-2026-0508', NULL,
 'DRAFT_READY', 'STATUS_UPDATE',
 E'Dear Mr Evans,\n\nJob MID-2026-0508 is currently at the Calais border inspection post (lane 3). We are monitoring clearance and will update you the moment it moves.\n\nKind regards,\nMidlands Haulage & Logistics',
 NULL, 0.6,
 NOW() + INTERVAL '3 hours', false, false, NULL, NULL,
 NOW() - INTERVAL '5 hours', NOW() - INTERVAL '4 hours', 1),

-- INVOICE_QUERY
(900023, 90001, '<demo-900023@acmeparts.example.com>', 'accounts@acmeparts.example.com',
 'Query on haulage invoice INV-7741',
 E'There is a waiting-time charge on invoice INV-7741 we did not expect. Can you clarify?\n\nAccounts, Acme Parts',
 NULL, NULL,
 'DRAFT_READY', 'INVOICE_QUERY',
 E'Dear Accounts Team,\n\nThe waiting-time charge on INV-7741 reflects driver detention beyond the free loading window. We will send the timed POD and tachograph extract to support it.\n\nKind regards,\nMidlands Haulage & Logistics',
 NULL, 0.4,
 NOW() + INTERVAL '7 hours', false, false, NULL, NULL,
 NOW() - INTERVAL '26 hours', NOW() - INTERVAL '25 hours', 1),

-- DOCUMENT_REQUEST, POD on file → attached
(900024, 90001, '<demo-900024@brightlogistics.example.com>', 'mark.evans@brightlogistics.example.com',
 'POD for MID-2026-0523 please',
 E'Please send the signed POD for delivery MID-2026-0523 (JLR Solihull).\n\nMark Evans',
 'MID-2026-0523', NULL,
 'DRAFT_READY', 'DOCUMENT_REQUEST',
 E'Dear Mr Evans,\n\nPlease find attached the signed proof of delivery for MID-2026-0523 (JLR Solihull). Let us know if anything further is needed.\n\nKind regards,\nMidlands Haulage & Logistics',
 NULL, 0.85,
 NOW() + INTERVAL '6 hours', false, false, 'POD', 90005,
 NOW() - INTERVAL '8 hours', NOW() - INTERVAL '7 hours', 1),

-- GENERAL
(900025, 90001, '<demo-900025@startup.example.com>', 'priya.patel@startup.example.com',
 'Do you do pallet networks?',
 E'Hi, we have ad-hoc pallets across the Midlands — do you offer a pallet network service?\n\nPriya Patel',
 NULL, NULL,
 'DRAFT_READY', 'GENERAL',
 E'Dear Ms Patel,\n\nYes — we run daily pallet collections across the Midlands via our network. Share your postcodes and pallet counts and we will set up an account rate.\n\nKind regards,\nMidlands Haulage & Logistics',
 NULL, 0.3,
 NOW() + INTERVAL '8 hours', false, false, NULL, NULL,
 NOW() - INTERVAL '40 hours', NOW() - INTERVAL '39 hours', 1),

-- IRRELEVANT (spam)
(900026, 90001, '<demo-900026@spam.example.com>', 'deals@spam.example.com',
 'Cheap diesel cards — sign up today',
 E'Save 12p per litre with our fuel card. Limited offer, click to apply!',
 NULL, NULL,
 'IRRELEVANT', NULL,
 NULL, NULL, NULL,
 NULL, false, false, NULL, NULL,
 NOW() - INTERVAL '5 days', NOW() - INTERVAL '5 days', 1)
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- Audit log — SEND / AUTO_SEND events so analytics (response time, automation rate,
-- AI-draft acceptance) light up. Attributed by first SEND/AUTO_SEND per ticket.
-- =============================================================================
DELETE FROM ticket_audit_log WHERE ticket_id >= 900000;  -- idempotent re-run (demo rows only)
INSERT INTO ticket_audit_log (tenant_id, ticket_id, actor_email, action, details, created_at) VALUES
    (1,     900002, 'system',                    'AUTO_SEND', 'Confidence 0.92 cleared threshold', NOW() - INTERVAL '1 day' + INTERVAL '3 minutes'),
    (1,     900006, 'operator@smartfreight.com', 'SEND',      'Operator sent (edited draft)',      NOW() - INTERVAL '3 days' + INTERVAL '40 minutes'),
    (1,     900010, 'operator@smartfreight.com', 'SEND',      'Quote sent',                        NOW() - INTERVAL '6 days' + INTERVAL '50 minutes'),
    (1,     900011, 'operator@smartfreight.com', 'SEND',      'Quote sent',                        NOW() - INTERVAL '4 days' + INTERVAL '35 minutes'),
    (1,     900013, 'system',                    'AUTO_SEND', 'Proactive milestone RELEASED',      NOW() - INTERVAL '12 hours' + INTERVAL '2 minutes'),
    (90001, 900021, 'system',                    'AUTO_SEND', 'Confidence 0.90 cleared threshold', NOW() - INTERVAL '2 days' + INTERVAL '2 minutes');

-- =============================================================================
-- Shipment watches (proactive status automation subscriptions).
-- =============================================================================
DELETE FROM shipment_watch WHERE customer_email LIKE '%example.com';  -- idempotent re-run (demo rows only)
INSERT INTO shipment_watch (tenant_id, identifier, customer_email, last_notified_status, active) VALUES
    (1,     'TEMU4448888', 'sara.ferry@ferryside.example.com', 'RELEASED',   true),
    (1,     'MSCU1234567', 'rachel.green@globaltrade.example.com', 'IN_TRANSIT', true),
    (90001, 'MID-2026-0481', 'sue.clark@acmeparts.example.com', 'IN_TRANSIT', true);

-- =============================================================================
-- Quote follow-ups (one converted → KPI, one silent → nudged).
-- =============================================================================
INSERT INTO quote_follow_up (tenant_id, ticket_id, customer_email, quote_sent_at, active) VALUES
    (1, 900010, 'rachel.green@globaltrade.example.com', NOW() - INTERVAL '6 days', false),  -- converted by 900006
    (1, 900011, 'tom.fisher@harbourline.example.com',   NOW() - INTERVAL '4 days', false)   -- nudged by 900012
ON CONFLICT (tenant_id, ticket_id) DO NOTHING;

-- =============================================================================
-- Training examples (captured operator edits — feeds retrieval + "learning" demo).
-- embedding left NULL: retrieval degrades to the recency fallback until nomic-embed-text
-- backfills these, and correctness is unaffected.
-- =============================================================================
DELETE FROM training_example WHERE source_ticket_id >= 900000;  -- idempotent re-run (demo rows only)
INSERT INTO training_example (tenant_id, source_ticket_id, ticket_type, enquiry_text, ai_draft, operator_final) VALUES
    (1, 900006, 'BOOKING_CONFIRM',
     'Thanks for the quote, please go ahead and book the 2 x 40HC Shanghai to Felixstowe.',
     'Dear Ms Green, Thank you for confirming. Your booking is being processed and we will send the details shortly.',
     'Dear Ms Green, Thank you — your booking for 2 x 40HC Shanghai to Felixstowe is confirmed. Our bookings desk will send the booking reference and cut-off details shortly.'),
    (1, 900099, 'RFQ',
     'Can you price a 20ft Rotterdam to Felixstowe?',
     'Dear customer, our rate is GBP 850. Regards.',
     'Dear Sir, thank you for your enquiry. Our all-in rate for a 20ft Rotterdam to Felixstowe is GBP 918 (incl. margin), valid 30 days. We would be glad to book this for you. Kind regards, Felixstowe Freight Solutions');
