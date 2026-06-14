package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** Matches {@link UserManagementService}'s minimum — same rule, same UI hint. */
    private static final int MIN_PASSWORD_LENGTH = 8;

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
     * Onboards a new tenant: the {@code tenant} row plus its first operator in Keycloak.
     *
     * @param name          tenant (organisation) name — must be unique
     * @param adminEmail    login email for the tenant's first operator — globally unique in Keycloak
     * @param adminPassword initial password — set non-temporary in Keycloak
     * @return the created tenant
     * @throws IllegalArgumentException on blank/duplicate name, taken email or short password
     */
    @Transactional
    public Tenant createTenant(String name, String adminEmail, String adminPassword) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tenant name must not be blank.");
        }
        String trimmedName = name.trim();
        if (tenantRepository.findByName(trimmedName).isPresent()) {
            throw new IllegalArgumentException("Tenant '" + trimmedName + "' already exists.");
        }
        if (adminPassword == null || adminPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long.");
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
        keycloakAdminService.createUser(email, adminPassword, tenant.getId(), "OPERATOR");
        return tenant;
    }
}
