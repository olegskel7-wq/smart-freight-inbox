package com.smartfreight.inbox.repository;

/**
 * Native-query projection: one bar of the AI-confidence histogram (Phase 2.1).
 *
 * Backed by {@link EmailTicketRepository#confidenceHistogramSince}. Bucket {@code i} covers
 * confidences {@code [i/10, (i+1)/10)}; the SQL folds the exact value 1.0 into bucket 9 so
 * the histogram is always ten bars. Empty buckets are absent from the result.
 */
public interface ConfidenceBucket {

    /** Bucket index 0..9. */
    int getBucket();

    long getCount();
}
