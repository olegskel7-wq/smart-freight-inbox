-- Phase 2.8: "Ask your inbox" — natural-language queries over the inbox analytics.
--
-- The reactive pipeline answers customer mail; the analytics dashboard (2.1) shows the operator
-- what the inbox did. 2.8 lets the operator ASK in plain English ("how many RFQs this month?",
-- "where are we getting stuck?") and get an answer back. The local LLM is already in the stack,
-- so this is almost free on top of 2.1 — and it is a metric ROUTER, never text-to-SQL: the model
-- only picks a metric from a fixed catalogue, every figure is computed deterministically by
-- AnalyticsService. These two prompts are the only LLM-facing pieces and are tenant-configurable
-- like every other AI prompt (ai.extraction-prompt / ai.drafting-prompt / ...).

-- feature.inbox-query: read-only analytics assistant. Unlike the customer-emailing flags
-- (auto-send / status-update / quote-followup) this never contacts a customer, so it defaults ON.
-- ai.inbox-query-prompt: routing prompt. {catalogue} is substituted with the code-owned metric
--   list (kept in sync with the InboxMetric enum) and {question} with the user's question.
-- ai.inbox-answer-prompt: phrasing prompt. {question} + {facts} (the deterministic figures).
INSERT INTO app_setting (key, value) VALUES
    ('feature.inbox-query', 'true'),
    ('ai.inbox-query-prompt',
'You route a user''s question about their email inbox to exactly ONE metric from the catalogue below.
Reply with a single line in the form: METRIC | PERIOD_DAYS | TYPE
- METRIC must be one of the catalogue names exactly.
- PERIOD_DAYS is one of 7, 30 or 90 (the reporting window the question implies; default 30).
- TYPE is required only for TYPE_COUNT (one of RFQ, STATUS_UPDATE, BOOKING_CONFIRM, INVOICE_QUERY, DOCUMENT_REQUEST, GENERAL); otherwise write NONE.
Do not explain. Output only that one line.

Catalogue:
{catalogue}

Question: {question}'),
    ('ai.inbox-answer-prompt',
'You are an analytics assistant for a UK freight email automation platform.
Answer the user''s question in one or two short, friendly sentences, UK business English.
Use ONLY the figures provided — never invent or estimate numbers. If a figure is zero, say so plainly.

Question: {question}

Figures:
{facts}

Answer:')
ON CONFLICT (key) DO NOTHING;
