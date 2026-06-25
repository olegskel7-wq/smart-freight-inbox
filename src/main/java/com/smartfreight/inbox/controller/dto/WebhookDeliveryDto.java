package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.entity.WebhookDelivery;

import java.time.LocalDateTime;

/**
 * One delivery attempt row for the React webhook delivery log (Phase 3.5). The payload body is omitted
 * from the list view (it is large and the same across a tenant's deliveries of one event); status,
 * attempts, the HTTP code and the last error are what the operator needs to debug a failing endpoint.
 */
public record WebhookDeliveryDto(
        Long id,
        String eventType,
        String status,
        int attempts,
        Integer responseStatus,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime nextAttemptAt,
        LocalDateTime deliveredAt) {

    public static WebhookDeliveryDto from(WebhookDelivery d) {
        return new WebhookDeliveryDto(
                d.getId(), d.getEventType(), d.getStatus().name(), d.getAttempts(),
                d.getResponseStatus(), d.getLastError(), d.getCreatedAt(),
                d.getNextAttemptAt(), d.getDeliveredAt());
    }
}
