package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.User;
import com.smartfreight.inbox.repository.UserRepository;
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

    /** Returns every active user (no pagination — the operator team is expected to be small). */
    public List<User> findAll() {
        return userRepository.findAll();
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
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
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
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (user.getEmail().equalsIgnoreCase(currentUserEmail)) {
            throw new IllegalArgumentException("You cannot delete your own account.");
        }
        userRepository.delete(user);
    }
}
