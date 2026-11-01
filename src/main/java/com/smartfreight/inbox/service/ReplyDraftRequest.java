package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TrackedItem;

/**
 * Input for a reply draft: the customer's email plus the context blocks that ground it.
 * A null or blank context block means nothing matched — the prompt then says so in that slot.
 *
 * @param emailBody        customer email text (body + parsed attachments)
 * @param item             tracked-item data (may be null if not found)
 * @param rateContext      matched rate lines from {@link RateSheetService}, or empty
 * @param exampleContext   few-shot example block from {@link FeedbackLoopService}, or empty
 * @param knowledgeContext knowledge block from {@link KnowledgeBaseService}, or empty
 */
public record ReplyDraftRequest(String emailBody, TrackedItem item, String rateContext,
                                String exampleContext, String knowledgeContext) {

    /** A request with no rate, example or knowledge context. */
    public static ReplyDraftRequest of(String emailBody, TrackedItem item) {
        return new ReplyDraftRequest(emailBody, item, "", "", "");
    }

    /** Lengths and the item reference only — the email body is customer content and must not reach logs. */
    @Override
    public String toString() {
        return "ReplyDraftRequest[emailBody=" + length(emailBody)
                + " chars, item=" + (item == null ? "none" : item.getIdentifier())
                + ", rateContext=" + length(rateContext) + " chars, exampleContext=" + length(exampleContext)
                + " chars, knowledgeContext=" + length(knowledgeContext) + " chars]";
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }
}
