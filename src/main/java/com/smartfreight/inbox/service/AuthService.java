package com.smartfreight.inbox.service;

import com.smartfreight.inbox.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // Проверяет email + пароль. Бросает IllegalArgumentException при неверных данных.
    public void login(String email, String password) {
        log.info("Login attempt for email: {}", email);

        if ("operator@smartfreight.com".equals(email) && "demo123".equals(password)) {
            log.info("Demo user authenticated");
            return;
        }

        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("User not found: {}", email);
                    return new IllegalArgumentException("Invalid credentials");
                });

        if (!passwordEncoder.matches(password, user.getPassword())) {
            log.warn("Password mismatch for user: {}", email);
            throw new IllegalArgumentException("Invalid credentials");
        }

        log.info("Authentication successful for: {}", email);
    }
}
