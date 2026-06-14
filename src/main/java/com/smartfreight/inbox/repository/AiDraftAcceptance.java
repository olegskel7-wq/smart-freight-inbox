package com.smartfreight.inbox.repository;

/**
 * Native-query projection: AI-draft acceptance over a reporting period (Phase 3.1 S3).
 *
 * Backed by {@link EmailTicketRepository#aiDraftAcceptanceSince}. {@code total} is the number of
 * replies first sent in the period that carried an AI draft; {@code accepted} is how many went out
 * exactly as the AI drafted them (the rest were edited before sending). Both are 0 when nothing
 * matching was sent.
 */
public interface AiDraftAcceptance {

    /** In-scope replies sent in the period (those with an AI draft). */
    long getTotal();

    /** Replies that went out unchanged from the AI draft. */
    long getAccepted();
}
