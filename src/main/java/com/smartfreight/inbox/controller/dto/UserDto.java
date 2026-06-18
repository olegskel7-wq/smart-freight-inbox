package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.service.KeycloakAdminService.KeycloakUser;

/**
 * One operator account for the React {@code /users} table (Phase 3.2). Mirrors the
 * {@link KeycloakUser} projection but drops the internal {@code tenantId} — the list is already
 * tenant-scoped by {@code UserManagementService}, so the client never needs it. {@code role} may be
 * null (the SPA falls back to "OPERATOR", same as the Thymeleaf page).
 */
public record UserDto(String id, String email, String role) {
    public static UserDto from(KeycloakUser u) {
        return new UserDto(u.id(), u.email(), u.role());
    }
}
