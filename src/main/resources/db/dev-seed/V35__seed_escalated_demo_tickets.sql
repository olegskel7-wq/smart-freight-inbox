-- Phase 3.10 demo: a few pre-escalated tickets so the smart-routing UI (Urgent/Angry badges +
-- escalated-first ordering) is visible on seed data without driving fresh mail through the pipeline.
-- urgency/sentiment are normally set by the classifier at processing time; these rows set them directly.
--
-- Dev-seed only (dev profile) — the prod docker backend does not apply it, but serves the rows since it
-- is the same DB. Idempotent (ON CONFLICT DO NOTHING on the PK); ids ≥ 900000 and *example.com keep them
-- inside the demo-row cleanup pattern (see V31). urgency is the Urgency ORDINAL: 1 = NORMAL, 2 = HIGH.
INSERT INTO email_ticket
    (id, tenant_id, message_id, sender_email, subject, body, extracted_identifier, attachment_text,
     status, ticket_type, draft_reply, ai_original_draft, ai_confidence,
     sla_deadline_at, sla_breached, proactive, requested_doc_type, attached_document_id,
     created_at, updated_at, version, urgency, sentiment_negative)
VALUES
-- Felixstowe (tenant 1): HIGH urgency + negative tone — stuck container, demurrage, unhappy customer.
(900031, 1, '<demo-900031@globaltrade.example.com>', 'rachel.green@globaltrade.example.com',
 'URGENT: HLXU9988776 still stuck at port — demurrage piling up',
 E'This is now the THIRD time I am chasing. Container HLXU9988776 has been sitting at Felixstowe for a week and we are being charged demurrage daily. I need it released TODAY or we will be looking for another forwarder.\n\nRachel Green\nGlobalTrade Imports',
 'HLXU9988776', NULL,
 'DRAFT_READY', 'STATUS_UPDATE',
 E'Dear Ms Green,\n\nApologies for the delay and frustration. HLXU9988776 has cleared and is available for collection at Felixstowe Terminal 2; I have asked the team to waive the latest demurrage day. I will call you within the hour to confirm.\n\nKind regards,\nFelixstowe Freight Solutions',
 NULL, 0.55,
 NOW() + INTERVAL '30 minutes', false, false, NULL, NULL,
 NOW() - INTERVAL '40 minutes', NOW() - INTERVAL '35 minutes', 1, 2, true),

-- Felixstowe (tenant 1): HIGH urgency, calm — time-critical quote, sailing closes.
(900032, 1, '<demo-900032@oceanline.example.com>', 'ingrid.larsen@oceanline.example.com',
 'Need rate ASAP — Felixstowe to Rotterdam, sailing closes tomorrow',
 E'Hi, could you quote 1 x 40HC Felixstowe to Rotterdam? The sailing closes tomorrow midday so I need this quickly to book in time. Thanks, Ingrid',
 NULL, NULL,
 'DRAFT_READY', 'RFQ',
 E'Dear Ms Larsen,\n\nFor 1 x 40HC Felixstowe to Rotterdam our rate is GBP 640 all-in, valid for this sailing. Let me know to book and I will secure the slot today.\n\nKind regards,\nFelixstowe Freight Solutions',
 NULL, 0.7,
 NOW() + INTERVAL '60 minutes', false, false, NULL, NULL,
 NOW() - INTERVAL '20 minutes', NOW() - INTERVAL '18 minutes', 1, 2, false),

-- Felixstowe (tenant 1): NORMAL urgency, negative tone — billing complaint.
(900033, 1, '<demo-900033@tradelink.example.com>', 'peter.jones@tradelink.example.com',
 'Very disappointed — overcharged again on invoice INV-4471',
 E'I am extremely unhappy. Invoice INV-4471 charges us for handling we never agreed to, and this is the second month running. Please explain or correct it.\n\nPeter Jones\nTradeLink Ltd',
 NULL, NULL,
 'DRAFT_READY', 'INVOICE_QUERY',
 E'Dear Mr Jones,\n\nI am sorry for the repeated billing error. I have reviewed INV-4471, the handling line was applied incorrectly, and a corrected invoice is on its way. Please accept my apologies.\n\nKind regards,\nFelixstowe Freight Solutions',
 NULL, 0.6,
 NOW() + INTERVAL '4 hours', false, false, NULL, NULL,
 NOW() - INTERVAL '2 hours', NOW() - INTERVAL '110 minutes', 1, 1, true),

-- Midlands Haulage (tenant 90001): HIGH urgency + negative tone — road delivery missed, angry.
(900034, 90001, '<demo-900034@midlands-retail.example.com>', 'sarah.bennett@midlands-retail.example.com',
 'Delivery MID-2026-0455 missed AGAIN — this is unacceptable',
 E'Your driver did not show for MID-2026-0455 and our store has staff waiting. This is the second failed delivery this week and it is costing us money. I want a call from a manager now.\n\nSarah Bennett\nMidlands Retail Group',
 'MID-2026-0455', NULL,
 'DRAFT_READY', 'STATUS_UPDATE',
 E'Dear Ms Bennett,\n\nI am very sorry — MID-2026-0455 has been re-scheduled for first drop tomorrow AM and our operations manager will call you within the hour to put this right.\n\nKind regards,\nMidlands Haulage & Logistics',
 NULL, 0.55,
 NOW() + INTERVAL '30 minutes', false, false, NULL, NULL,
 NOW() - INTERVAL '25 minutes', NOW() - INTERVAL '20 minutes', 1, 2, true)
ON CONFLICT (id) DO NOTHING;
