# 6. Human in the loop by default; one gate for sending without an operator

**Status:** accepted · 2026-06

## Context
A wrong automated email to a customer costs more than a slow one. But status questions grounded in
real data are safe to answer instantly, and that is where the time saving is.

## Decision
Every draft goes to an operator unless **all three** checks pass: `feature.auto-send` is on (default
OFF), the ticket type is allow-listed (default: STATUS_UPDATE only), and the **confidence** clears
the threshold (0.8). Confidence is a deterministic heuristic (type known, identifier found, item
found, rate matched), stored on every draft. The SMTP send happens strictly after the pipeline
transaction commits.

## Consequences
- Auto-send is explainable: the operator can see why a ticket did or did not go out.
- Proactive emails (status updates, quote follow-ups) reuse the same gate ([ADR 9](0009-proactive-emails.md)).
- An SMTP failure leaves the ticket DRAFT_READY and raises an alert instead of losing it.
