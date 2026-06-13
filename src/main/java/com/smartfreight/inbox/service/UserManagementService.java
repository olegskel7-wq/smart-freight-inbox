package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.User;
import com.smartfreight.inbox.repository.UserRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD facade for {@link User} accounts used by the operator self-service UI.
 *
 * <p>Backed by the same {@code app_user} table that
 * {@link com.smartfreight.inbox.config.DbUserDetailsService} reads at login.
 * Password values are stored as BCrypt hashes; raw passwords never touch the DB.
 *
 * <p>All mutating methods validate the input and run inside a transaction —
 * exceptions thrown here bubble up to the controller as flash error messages.
 *
 * <p>Phase 2.2: {@code app_user} sits OUTSIDE {@code @TenantId}/RLS (login bootstrap
 * table), so tenant scoping is enforced HERE, explicitly: lists are filtered by the
 * caller's tenant, created users inherit it, and cross-tenant targets read as
 * "not found". Email stays globally unique across tenants (it is the login key).
 */
@Service
public class UserManagementService {

    /** Minimum acceptable password length — short enough for usability, long enough to discourage trivial guesses. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Returns the caller's tenant users (no pagination — the operator team is expected to be small). */
    public List<User> findAll() {
        return userRepository.findByTenantId(currentTenantId());
    }

    /**
     * Creates a new operator account. Email is normalised (trim + lowercase) before persisting.
     *
     * @param email       login email — must be unique
     * @param rawPassword plain-text password — encoded with BCrypt before storage
     * @param role        Spring Security role name (without the {@code ROLE_} prefix)
     * @throws IllegalArgumentException if the password is too short or the email is already taken
     */
    @Transactional
    public void createUser(String email, String rawPassword, String role) {
        validatePassword(rawPassword);
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("User with email " + email + " already exists.");
        }
        User user = User.builder()
                .email(email.trim().toLowerCase())
                .password(passwordEncoder.encode(rawPassword))
                .role(role)
                .tenantId(currentTenantId())
                .build();
        userRepository.save(user);
    }

    /**
     * Replaces the password for an existing user. Used both for self-service password change
     * and for operator-on-operator resets — authorisation is enforced at the controller layer.
     *
     * @param userId      target user
     * @param rawPassword new plain-text password
     * @throws IllegalArgumentException if the password is too short or the user does not exist
     */
    @Transactional
    public void changePassword(Long userId, String rawPassword) {
        validatePassword(rawPassword);
        User user = findInCurrentTenant(userId);
        user.setPassword(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
    }

    /** Cheap sanity check — does not enforce a complex password policy (out of scope for the pilot). */
    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long.");
        }
    }

    /**
     * Deletes a user account. Prevents an operator from deleting themselves —
     * accidental self-lockout would leave the system without any way to log in.
     *
     * @param userId           target user
     * @param currentUserEmail email of the operator performing the action
     * @throws IllegalArgumentException if the target does not exist or matches the caller
     */
    @Transactional
    public void deleteUser(Long userId, String currentUserEmail) {
        User user = findInCurrentTenant(userId);
        if (user.getEmail().equalsIgnoreCase(currentUserEmail)) {
            throw new IllegalArgumentException("You cannot delete your own account.");
        }
        userRepository.delete(user);
    }

    /** Loads a user only if they belong to the caller's tenant — others read as "not found". */
    private User findInCurrentTenant(Long userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getTenantId().equals(currentTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
    }

    private Long currentTenantId() {
        // S3: user management is only reachable by an authenticated operator, whose
        // tenant TenantFilter has already bound — an absent tenant is a bug, not default.
        return TenantContext.requireTenantId();
    }
}
