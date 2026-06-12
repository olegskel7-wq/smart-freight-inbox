package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.User;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Platform-level tenant administration (Phase 2.2 S2) — used by {@code /admin/tenants},
 * which is gated to SUPER_ADMIN in SecurityConfig.
 *
 * <p>The {@code tenant} table itself carries no RLS (it is the catalogue the policies key
 * on), so listing here is intentionally cross-tenant. Onboarding creates the tenant and
 * its first operator in one transaction: a tenant without a login would be unreachable —
 * settings, rates and (from S3) mailboxes are all configured from inside the tenant.
 *
 * <p>Deletion is deliberately absent: a tenant owns tickets, audit history and rates;
 * removing one is a data-retention decision (Phase 4 GDPR work), not a button.
 */
@Service
public class TenantManagementService {

    /** Matches {@link UserManagementService}'s minimum — same rule, same UI hint. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public TenantManagementService(TenantRepository tenantRepository, UserRepository userRepository,
                                   PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** One row of the platform admin overview. */
    public record TenantSummary(Tenant tenant, long userCount) {
    }

    /** All tenants with their user counts, oldest first (Default tenant on top). */
    public List<TenantSummary> overview() {
        return tenantRepository.findAll().stream()
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .map(t -> new TenantSummary(t, userRepository.countByTenantId(t.getId())))
                .toList();
    }

    /**
     * Onboards a new tenant: the tenant row plus its first operator account, atomically.
     *
     * @param name          tenant (organisation) name — must be unique
     * @param adminEmail    login email for the tenant's first operator — globally unique
     * @param adminPassword plain-text password — BCrypt-encoded before storage
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
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("User with email " + email + " already exists.");
        }

        Tenant tenant = tenantRepository.save(Tenant.builder().name(trimmedName).build());
        userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(adminPassword))
                .role("OPERATOR")
                .tenantId(tenant.getId())
                .build());
        return tenant;
    }
}
