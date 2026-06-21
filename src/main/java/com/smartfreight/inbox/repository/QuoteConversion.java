package com.smartfreight.inbox.repository;

/**
 * Native-query projection: quote-to-booking conversion over a reporting period (Phase 3.6).
 *
 * Backed by {@link QuoteFollowUpRepository#quoteConversionSince}. {@code quotes} is the number of
 * RFQ quotes sent in the period (one per {@code quote_follow_up} row, armed at every send site);
 * {@code converted} is how many of those were followed by an inbound BOOKING_CONFIRM from the same
 * customer. Both are 0 when no quote went out in the period.
 *
 * <p>Counting is per-quote (each sent quote is its own row, the natural "did this quote convert"
 * unit) and matching is sender-based (the booking's address must contain the quoted address) —
 * the same address-match limitation accepted for the Phase 2.5 follow-up reply detection.
 */
public interface QuoteConversion {

    /** RFQ quotes sent in the period. */
    long getQuotes();

    /** Quotes followed by a booking from the same customer. */
    long getConverted();
}
