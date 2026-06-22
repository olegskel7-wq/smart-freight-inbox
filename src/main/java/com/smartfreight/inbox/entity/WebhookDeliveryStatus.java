package com.smartfreight.inbox.entity;

/**
 * Lifecycle of a single {@link WebhookDelivery} (Phase 3.5).
 *
 * <p>{@code PENDING} rows are what the dispatcher polls; a 2xx moves the row to {@code DELIVERED},
 * and a row that exhausts its attempt budget becomes {@code DEAD} (kept for the UI log, never retried).
 */
public enum WebhookDeliveryStatus {
    PENDING,
    DELIVERED,
    DEAD
}
