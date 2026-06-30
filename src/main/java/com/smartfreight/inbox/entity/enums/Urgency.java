package com.smartfreight.inbox.entity.enums;

/**
 * How time-critical an incoming email is (Phase 3.10 — smart routing).
 *
 * Assigned by the AI classifier in the same Qwen call that decides relevance and type:
 * one call now returns {@code TYPE|URGENCY|SENTIMENT}. Urgency drives prioritisation —
 * a tighter SLA window for HIGH (see {@code SlaService}) and escalated-first ordering in
 * the operator list — but never changes the AI draft itself.
 *
 * <p><strong>Ordinal order is load-bearing.</strong> The values are persisted with
 * {@link jakarta.persistence.EnumType#ORDINAL} (LOW=0, NORMAL=1, HIGH=2) precisely so that
 * {@code ORDER BY urgency DESC} surfaces the most urgent tickets first as a plain indexed
 * column — a STRING mapping would sort alphabetically (HIGH/LOW/NORMAL) and break the queue
 * ordering. Never reorder these constants or insert in the middle; append only.
 *
 * <p>{@link #NORMAL} is the default for every ticket (column default + fail-open verdict),
 * so legacy rows and classifier outages read as a normal-priority email, never lost or
 * spuriously escalated.
 */
public enum Urgency {

    /** Routine — no time pressure beyond the per-type SLA window. */
    LOW,

    /** Default priority: the standard per-type SLA window applies. Also the fail-open value. */
    NORMAL,

    /** Time-critical — tightens the SLA window and floats the ticket to the top of the queue. */
    HIGH
}
