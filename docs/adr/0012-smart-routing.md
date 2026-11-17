# 12. Urgency and sentiment from the same classifier call

**Status:** accepted · 2026-06

## Context
Angry or time-critical emails should be handled first, but a second LLM call per email would double
the cost of classification.

## Decision
The classifier answers `TYPE|URGENCY|SENTIMENT` in **one** call, parsed leniently (defaults: NORMAL
urgency, calm tone). *Escalated* means HIGH urgency or a negative tone and is defined once
(`ClassificationResult.isEscalated`). An escalated ticket gets half the SLA window, sorts to the top
of the default list and, if unassigned, is auto-assigned to the tenant's escalation operator. The
LLM never picks people — routing is a deterministic rule.

## Consequences
- No extra LLM cost; manual assignments are never overwritten.
- `urgency` is stored as an ordinal SMALLINT, so it sorts numerically.
