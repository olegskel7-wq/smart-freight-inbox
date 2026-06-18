package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.service.TenantManagementService.TenantSummary;

import java.time.LocalDateTime;

/**
 * One tenant row for the React {@code /admin/tenants} table (Phase 3.2). Flattens the
 * {@link TenantSummary} projection (the {@link com.smartfreight.inbox.entity.Tenant} entity plus its
 * Keycloak operator count) into the four columns the page renders, so the entity itself never crosses
 * the wire.
 */
public record TenantSummaryDto(Long id, String name, long userCount, LocalDateTime createdAt) {
    public static TenantSummaryDto from(TenantSummary s) {
        return new TenantSummaryDto(
                s.tenant().getId(),
                s.tenant().getName(),
                s.userCount(),
                s.tenant().getCreatedAt());
    }
}
