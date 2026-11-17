# 11. "Ask your inbox" routes to metrics; it never writes SQL

**Status:** accepted · 2026-06

## Context
Managers want plain-English questions answered ("what share of quotes booked last month?"). A 7B
model writes unreliable SQL, and the product promises correct numbers.

## Decision
The LLM only **routes** the question to one metric from a fixed catalogue (`InboxMetric`), plus a
period and an optional type. `AnalyticsService` computes the figures deterministically (RLS-scoped),
and a second call rephrases them. If the model is unavailable, the deterministic headline is
returned as is.

## Consequences
- An answer is either correct or a polite "can't answer" — never a wrong number.
- New questions mean new catalogue entries, not prompt tweaks.
