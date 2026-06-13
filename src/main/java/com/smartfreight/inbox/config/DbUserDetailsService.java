package com.smartfreight.inbox.config;

import com.smartfreight.inbox.repository.UserRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import com.smartfreight.inbox.tenancy.TenantUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * {@link UserDetailsService} implementation backed by the database (table {@code app_user}).
 *
 * Replaces the previous {@code InMemoryUserDetailsManager} with a hard-coded demo user.
 * Spring Security now reads user data from a single source at login time
 * (both via form login {@code /login} and via REST {@code /api/auth/login}) —
 * the {@code app_user} table.
 *
 * Advantages:
 * — single source of truth for users (DB, not in-memory + hard-code);
 * — users are added via Flyway migrations (V2/V3 for demo user operator@smartfreight.com);
 * — ready for the future: password change, account lock, roles, multi-tenancy.
 *
 * Spring Security automatically picks up this bean as the sole {@link UserDetailsService}.
 *
 * Important: this class must NOT encode the password itself. The encoded password is already in the DB
 * (inserted by a Flyway migration with a BCrypt hash), and the comparison of the entered password
 * is performed by {@code DaoAuthenticationProvider} via {@link org.springframework.security.crypto.password.PasswordEncoder}.
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(DbUserDetailsService.class);

    /** Default role assigned when the user's {@code role} field in the DB is NULL. */
    private static final String DEFAULT_ROLE = "OPERATOR";

    /** Platform admin (Phase 2.2 S2) — manages tenants, implies OPERATOR. */
    public static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";

    private final UserRepository userRepository;

    public DbUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Loads user data by login name (email address).
     *
     * Called automatically by Spring Security during authentication.
     * The returned {@link UserDetails} object carries the email, BCrypt hash,
     * and role list; password comparison is performed by Spring Security.
     *
     * Security notes:
     * — {@link UsernameNotFoundException} is thrown with a generic message —
     *   the client is not told whether the user exists (logs contain the detail);
     * — if no role is set in the DB, the safe default OPERATOR is assigned
     *   to avoid a "user without a role" state (Spring Security complains in that case).
     *
     * @param email email address — used as the login name
     * @return {@link UserDetails} object understood by Spring Security
     * @throws UsernameNotFoundException if no user with this email was found
     */
    @Override
    public UserDetails loadUserByUsername(String email) {
        // Login is inherently cross-tenant: it runs BEFORE TenantFilter can bind a tenant
        // (inside the security chain) and its whole job is to discover which tenant the
        // user belongs to. Under S3 fail-loud semantics an undeclared context would throw
        // at session open, so the lookup states its nature explicitly. app_user itself is
        // outside @TenantId/RLS — system here widens nothing.
        return TenantContext.callAsSystem(() -> userRepository.findByEmail(email))
                .map(u -> {
                    String role = (u.getRole() == null || u.getRole().isBlank())
                            ? DEFAULT_ROLE
                            : u.getRole();
                    // SUPER_ADMIN (platform admin, Phase 2.2 S2) keeps full operator
                    // access on top of /admin/tenants — grant both authorities rather
                    // than introduce a role-hierarchy bean for a single pair.
                    String[] roles = SUPER_ADMIN_ROLE.equals(role)
                            ? new String[]{"ROLE_" + SUPER_ADMIN_ROLE, "ROLE_" + DEFAULT_ROLE}
                            : new String[]{"ROLE_" + role};
                    // Phase 2.2: the principal carries the tenant so TenantFilter never
                    // needs a DB lookup — the tenant is fixed for the session at login.
                    return (UserDetails) new TenantUserDetails(
                            u.getEmail(),
                            u.getPassword(),
                            AuthorityUtils.createAuthorityList(roles),
                            u.getTenantId());
                })
                .orElseThrow(() -> {
                    log.warn("User not found in database: {}", email);
                    return new UsernameNotFoundException("User not found: " + email);
                });
    }
}
