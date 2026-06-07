-- Phase 0 (universalisation): move AI prompts out of hardcoded Java into app_setting,
-- so prompts can be tuned per vertical (freight, estate agents, legal, ...) without a rebuild.

-- Prompt templates are longer than the original VARCHAR(500); widen to TEXT.
ALTER TABLE app_setting ALTER COLUMN value TYPE TEXT;

-- Seed the two pipeline prompts. Placeholders are substituted at runtime:
--   {email}       — the customer email body (truncated)
--   {itemContext} — looked-up tracked-item data, or a "not found" note (drafting only)
INSERT INTO app_setting (key, value) VALUES
    ('ai.extraction-prompt',
'You are a logistics assistant. Extract the shipping container number from the email below.
Container numbers follow ISO 6346 format: exactly 4 uppercase letters followed by 7 digits (e.g. MSCU1234567).
Return ONLY the container number, nothing else. If no container number is found, return "NOT_FOUND".

Email:
{email}'),
    ('ai.drafting-prompt',
'You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
Write a concise, professional email reply to the customer''s inquiry below.
Use the container information provided. Be helpful, polite, and factual.
Do NOT make up information that is not in the container data.
Reply in the same language as the customer''s email.

Container information:
{itemContext}

Customer''s email:
{email}

Write only the email reply body (no subject line, no "Dear..." header unless natural).')
ON CONFLICT (key) DO NOTHING;
