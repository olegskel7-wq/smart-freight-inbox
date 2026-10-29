package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;

/**
 * Platform-level tenant administration (Phase 2.2 S2, reworked for Keycloak in Phase 2.3 S2) —
 * used by {@code /admin/tenants}, gated to SUPER_ADMIN in SecurityConfig.
 *
 * <p>The {@code tenant} table carries no RLS (it is the catalogue the policies key on), so the
 * overview is intentionally cross-tenant. Onboarding still creates the {@code tenant} row in our
 * DB — Postgres RLS needs it — but the first operator is now created in <b>Keycloak</b> (the sole
 * identity provider) with a {@code tenant_id} attribute, not in {@code app_user}.
 *
 * <p>The tenant row save and the Keycloak user creation span two systems and cannot share one
 * transaction. The method is ordered so the Keycloak call is last and runs inside the DB
 * transaction: if it fails the {@code tenant} row insert rolls back, leaving no orphan tenant.
 * (The only residual gap is a Keycloak user created but whose role assignment then fails — rare,
 * and surfaced as an error to the admin.)
 *
 * <p>Deletion is deliberately absent: a tenant owns tickets, audit history and rates; removing
 * one is a data-retention decision (Phase 4 GDPR work), not a button.
 */
@Service
public class TenantManagementService {

    /**
     * The tenant's first user is its ADMIN (Gate B6): only ADMIN reaches /settings (mailboxes) and
     * /users, so an OPERATOR-only first user left a new tenant with no way to connect its mailbox.
     */
    static final String FIRST_USER_ROLE = "ADMIN";

    private final TenantRepository tenantRepository;
    private final KeycloakAdminService keycloakAdminService;

    public TenantManagementService(TenantRepository tenantRepository,
                                   KeycloakAdminService keycloakAdminService) {
        this.tenantRepository = tenantRepository;
        this.keycloakAdminService = keycloakAdminService;
    }

    /** One row of the platform admin overview. */
    public record TenantSummary(Tenant tenant, long userCount) {
    }

    /** All tenants with their Keycloak operator counts, oldest first (Default tenant on top). */
    public List<TenantSummary> overview() {
        return tenantRepository.findAll().stream()
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .map(t -> new TenantSummary(t, keycloakAdminService.countUsersByTenant(t.getId())))
                .toList();
    }

    /**
     * Onboards a new tenant: the {@code tenant} row plus its first user (the tenant ADMIN) in Keycloak.
     *
     * @param name          tenant (organisation) name — must be unique
     * @param adminEmail    login email for the tenant's first user — globally unique in Keycloak
     * @param adminName     the first user's first/last name — signs their replies
     * @param adminPassword initial password — set non-temporary in Keycloak
     * @return the created tenant
     * @throws IllegalArgumentException on blank/duplicate name, taken email, or a password or person
     *                                  name that fails our checks or Keycloak's
     */
    @Transactional
    public Tenant createTenant(String name, String adminEmail, OperatorName adminName, String adminPassword) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tenant name must not be blank.");
        }
        String trimmedName = name.trim();
        if (tenantRepository.findByName(trimmedName).isPresent()) {
            throw new IllegalArgumentException("Tenant '" + trimmedName + "' already exists.");
        }
        if (adminPassword == null || adminPassword.length() < UserManagementService.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + UserManagementService.MIN_PASSWORD_LENGTH + " characters long.");
        }
        if (adminEmail == null || adminEmail.isBlank()) {
            throw new IllegalArgumentException("Operator email must not be blank.");
        }
        String email = adminEmail.trim().toLowerCase();
        if (keycloakAdminService.emailExists(email)) {
            throw new IllegalArgumentException("User with email " + email + " already exists.");
        }

        Tenant tenant = tenantRepository.save(Tenant.builder().name(trimmedName).build());
        // Last step, still inside the TX: a Keycloak failure here rolls back the tenant row.
        try {
            keycloakAdminService.createUser(email, adminName, adminPassword, tenant.getId(), FIRST_USER_ROLE);
        } catch (WebClientResponseException.BadRequest e) {
            throw UserManagementService.rejection(e);
        }
        return tenant;
    }
}
