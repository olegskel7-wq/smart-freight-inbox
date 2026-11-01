package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TrackedItem;

/**
 * Static drafts {@link AiDraftingService} returns when Ollama is unavailable or answers with
 * nothing: basic information from the DB, no AI generation. Pure string building.
 */
final class DraftFallbacks {

    /**
     * Quote follow-up nudge — there is no {@link TrackedItem} here (a follow-up is about a quote,
     * not a shipment), so the item fallback does not apply.
     */
    static final String QUOTE_FOLLOW_UP = """
            Dear Customer,

            I hope you are well. I am following up on the freight quote we sent you recently.
            Please let me know if you have any questions or if you would like to go ahead — we would be glad to help.
            """;

    private DraftFallbacks() {
    }

    /**
     * Reply or status-update fallback with whatever item data is available.
     *
     * @param item tracked-item data or null
     */
    static String forItem(TrackedItem item) {
        if (item == null) {
            return """
                    Thank you for your enquiry.
                    Unfortunately, we were unable to locate the reference in our system at this time.
                    Please contact our team for further assistance.
                    """;
        }

        return String.format("""
                Thank you for your enquiry regarding %s.

                Current status: %s
                ETA: %s
                Location: %s

                Please do not hesitate to contact us if you require further information.
                """,
                item.getIdentifier(),
                item.getStatus() != null ? item.getStatus() : "N/A",
                item.getEta() != null ? item.getEta().format(DraftPrompts.ETA_FORMATTER) : "N/A",
                item.getLocation() != null ? item.getLocation() : "N/A"
        );
    }

    /** Document-request reply fallback, per branch: the document is attached, or being retrieved. */
    static String forDocument(String docLabel, boolean attached) {
        if (attached) {
            return String.format("""
                    Thank you for your enquiry.

                    Please find the requested %s attached to this email.

                    Do not hesitate to contact us if you require anything further.
                    """, docLabel);
        }
        return String.format("""
                Thank you for your enquiry.

                We have received your request for the %s and are retrieving it. We will send it to you shortly.
                """, docLabel);
    }
}
