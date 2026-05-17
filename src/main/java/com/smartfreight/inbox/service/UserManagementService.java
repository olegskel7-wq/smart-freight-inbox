package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.User;
import com.smartfreight.inbox.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserManagementService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

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

    @Transactional
    public void changePassword(Long userId, String rawPassword) {
        validatePassword(rawPassword);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setPassword(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
    }

    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters long.");
        }
    }

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
