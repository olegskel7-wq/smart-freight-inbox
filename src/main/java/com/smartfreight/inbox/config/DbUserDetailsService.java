package com.smartfreight.inbox.config;

import com.smartfreight.inbox.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        return userRepository.findByEmail(email)
                .map(u -> {
                    String role = (u.getRole() == null || u.getRole().isBlank())
                            ? DEFAULT_ROLE
                            : u.getRole();
                    return org.springframework.security.core.userdetails.User
                            .withUsername(u.getEmail())
                            .password(u.getPassword())
                            .roles(role)
                            .build();
                })
                .orElseThrow(() -> {
                    log.warn("User not found in database: {}", email);
                    return new UsernameNotFoundException("User not found: " + email);
                });
    }
}
