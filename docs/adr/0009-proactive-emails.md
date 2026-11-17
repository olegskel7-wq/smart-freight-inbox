# 9. Proactive emails reuse the reactive scaffold

**Status:** accepted · 2026-06

## Context
Customers ask "where is my container?" because nobody told them, and quotes go silent. Both are
outbound emails the system could start itself — but it must not spam.

## Decision
The inbound enquiry is the subscription: a grounded STATUS_UPDATE reply arms a `shipment_watch`, and
a sent RFQ reply arms a `quote_follow_up`. Schedulers (system → per tenant) poll the watched
identifiers for milestone changes or wait out the follow-up delay, then create an outbound
`proactive` ticket that passes through the **same auto-send gate** ([ADR 6](0006-human-in-the-loop-auto-send-gate.md)).
Both flags default to OFF.

## Consequences
- port-service stays a plain lookup backend; change detection is inbox-side polling of watched
  items only.
- One nudge per quote; a reply from the customer (matched by sender) cancels it.
- Proactive tickets reply from the mailbox the original enquiry came in on.
