package com.smartfreight.inbox.service;

import com.smartfreight.inbox.service.KeycloakAdminService.KeycloakUser;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Set;

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

    /** Minimum acceptable password length — matches the realm {@code passwordPolicy} (length(10)). */
    private static final int MIN_PASSWORD_LENGTH = 10;

    /**
     * User-facing description of the realm password policy (length(10) + upperCase + lowerCase +
     * digits + notUsername). Surfaced when Keycloak rejects a password we only length-checked, so the
     * operator gets the actual rule instead of an opaque 500 — the realm stays the single source of
     * truth for the full policy, we just translate its rejection.
     */
    private static final String PASSWORD_POLICY_MESSAGE =
            "Password does not meet the security policy: at least 10 characters, including an uppercase "
            + "letter, a lowercase letter and a digit, and it must not be the same as the email.";

    /**
     * Tenant-level roles an admin may hand out (Phase 3.3). SUPER_ADMIN is platform-level and only
     * granted via the realm seed — it must never be assignable from the tenant self-service UI.
     */
    private static final Set<String> ASSIGNABLE_ROLES = Set.of("OPERATOR", "ADMIN");

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
        validateRole(role);
        String normalised = email.trim().toLowerCase();
        if (keycloakAdminService.emailExists(normalised)) {
            throw new IllegalArgumentException("User with email " + normalised + " already exists.");
        }
        try {
            keycloakAdminService.createUser(normalised, rawPassword, currentTenantId(), role);
        } catch (WebClientResponseException.BadRequest e) {
            // Keycloak enforces the full realm passwordPolicy (upper/lower/digit/notUsername) that our
            // cheap length check cannot — translate its 400 into a clear message, not an opaque 500.
            throw new IllegalArgumentException(PASSWORD_POLICY_MESSAGE);
        }
    }

    /**
     * Changes an operator's tenant role (OPERATOR ↔ ADMIN, Phase 3.3). Refuses to change the
     * caller's own role — an admin demoting themselves could lock the tenant out of its own
     * management surface (mirrors the {@link #deleteUser} self-guard).
     *
     * @param userId           Keycloak user id (UUID)
     * @param role             new role — must be one of {@link #ASSIGNABLE_ROLES}
     * @param currentUserEmail email of the operator performing the action
     * @throws IllegalArgumentException if the role is invalid, the target is not in this tenant,
     *                                  or the target is the caller
     */
    public void changeRole(String userId, String role, String currentUserEmail) {
        validateRole(role);
        KeycloakUser user = requireInCurrentTenant(userId);
        if (user.email() != null && user.email().equalsIgnoreCase(currentUserEmail)) {
            throw new IllegalArgumentException("You cannot change your own role.");
        }
        keycloakAdminService.setRealmRole(userId, role);
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
        try {
            keycloakAdminService.resetPassword(userId, rawPassword);
        } catch (WebClientResponseException.BadRequest e) {
            throw new IllegalArgumentException(PASSWORD_POLICY_MESSAGE);
        }
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

    /** Rejects a role the tenant UI must not assign (anything but OPERATOR/ADMIN — notably SUPER_ADMIN). */
    private void validateRole(String role) {
        if (role == null || !ASSIGNABLE_ROLES.contains(role)) {
            throw new IllegalArgumentException("Invalid role: " + role);
        }
    }

    /**
     * Cheap length pre-check for instant feedback. The full character-class policy
     * (upper/lower/digit/notUsername) is enforced by Keycloak and surfaced via the
     * {@link #PASSWORD_POLICY_MESSAGE} translation — not duplicated here, to keep the realm the single
     * source of truth.
     */
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
