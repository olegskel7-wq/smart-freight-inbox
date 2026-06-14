package com.smartfreight.inbox.service;

import com.smartfreight.inbox.service.KeycloakAdminService.KeycloakUser;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * CRUD facade for operator accounts used by the self-service UI (Phase 2.2 S2,
 * reworked for Keycloak in Phase 2.3 S2).
 *
 * <p>Backing store is now <b>Keycloak</b> (decision 2, single realm): users carry a
 * {@code tenant_id} attribute and a realm role, and passwords live in Keycloak — they never
 * touch our DB. The legacy {@code app_user} table is gone (V27).
 *
 * <p>Tenant scoping is enforced HERE, explicitly: lists are filtered by the caller's tenant
 * (Keycloak attribute search), created users inherit it, and cross-tenant targets read as
 * "not found". Email stays globally unique across tenants (it is the login key, enforced by
 * the realm).
 */
@Service
public class UserManagementService {

    /** Minimum acceptable password length — matches the realm policy expectation for the pilot. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final KeycloakAdminService keycloakAdminService;

    public UserManagementService(KeycloakAdminService keycloakAdminService) {
        this.keycloakAdminService = keycloakAdminService;
    }

    /** Returns the caller's tenant operators (no pagination — the operator team is expected to be small). */
    public List<KeycloakUser> findAll() {
        return keycloakAdminService.listUsersByTenant(currentTenantId());
    }

    /**
     * Creates a new operator account in Keycloak, scoped to the caller's tenant.
     *
     * @param email       login email — must be globally unique
     * @param rawPassword initial password — stored by Keycloak (non-temporary)
     * @param role        realm role name (without the {@code ROLE_} prefix)
     * @throws IllegalArgumentException if the password is too short or the email is already taken
     */
    public void createUser(String email, String rawPassword, String role) {
        validatePassword(rawPassword);
        String normalised = email.trim().toLowerCase();
        if (keycloakAdminService.emailExists(normalised)) {
            throw new IllegalArgumentException("User with email " + normalised + " already exists.");
        }
        keycloakAdminService.createUser(normalised, rawPassword, currentTenantId(), role);
    }

    /**
     * Resets the password for an existing operator. Used for operator-on-operator resets —
     * authorisation is enforced at the controller layer.
     *
     * @param userId      Keycloak user id (UUID)
     * @param rawPassword new password
     * @throws IllegalArgumentException if the password is too short or the user is not in this tenant
     */
    public void changePassword(String userId, String rawPassword) {
        validatePassword(rawPassword);
        requireInCurrentTenant(userId);
        keycloakAdminService.resetPassword(userId, rawPassword);
    }

    /**
     * Deletes an operator account. Prevents an operator from deleting themselves — accidental
     * self-lockout would leave that tenant unable to manage its own users.
     *
     * @param userId           Keycloak user id (UUID)
     * @param currentUserEmail email of the operator performing the action
     * @throws IllegalArgumentException if the target is not in this tenant or matches the caller
     */
    public void deleteUser(String userId, String currentUserEmail) {
        KeycloakUser user = requireInCurrentTenant(userId);
        if (user.email() != null && user.email().equalsIgnoreCase(currentUserEmail)) {
            throw new IllegalArgumentException("You cannot delete your own account.");
        }
        keycloakAdminService.deleteUser(userId);
    }

    /** Cheap sanity check — does not enforce a complex password policy (delegated to Keycloak). */
    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long.");
        }
    }

    /** Loads a user only if they belong to the caller's tenant — others read as "not found". */
    private KeycloakUser requireInCurrentTenant(String userId) {
        return keycloakAdminService.findUser(userId)
                .filter(u -> currentTenantId().equals(u.tenantId()))
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
    }

    private Long currentTenantId() {
        // User management is only reachable by an authenticated operator, whose tenant
        // TenantFilter has already bound — an absent tenant is a bug, not a default.
        return TenantContext.requireTenantId();
    }
}
