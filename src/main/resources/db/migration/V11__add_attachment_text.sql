-- Phase 1.1 (attachment parsing): store text extracted from PDF/Excel attachments.
-- Kept separate from `body` so the operator UI can show the email and its attachment
-- content distinctly, while the AI pipeline combines them when building prompts.
ALTER TABLE email_ticket ADD COLUMN attachment_text TEXT;
