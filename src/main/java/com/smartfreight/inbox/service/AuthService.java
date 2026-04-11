package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.User;
import com.smartfreight.inbox.repository.UserRepository;
import com.smartfreight.inbox.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

// Сервис аутентификации: проверяет логин/пароль и выдаёт JWT токен.
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    // Проверяет email + пароль. При успехе возвращает JWT токен.
    // При неверных данных — бросает IllegalArgumentException (401 на уровне контроллера).
    public String login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        return jwtService.generateToken(email);
    }
}
