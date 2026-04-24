package com.smartfreight.inbox.service;

import com.smartfreight.inbox.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Сервис аутентификации пользователей.
 * 
 * Используется для:
 * - Проверки учётных данных при логине через REST (/api/auth/login)
 * - Проверки credentials перед выдачей токена или сессии
 * 
 * Поддерживает:
 * - Встроенного demo пользователя (operator@smartfreight.com / demo123)
 * - Поиск пользователей в БД через UserRepository
 * - Проверку паролей через BCryptPasswordEncoder (никогда не сравниваем открытые пароли)
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Проверяет учётные данные пользователя.
     * 
     * Алгоритм:
     * 1. Если email = operator@smartfreight.com и password = demo123 → успех (demo пользователь)
     * 2. Иначе ищем в БД UserRepository.findByEmail()
     * 3. Сравниваем пароль через BCryptPasswordEncoder (безопасное сравнение с солью)
     * 
     * Логирование: каждая попытка логируется для аудита.
     * Ошибки: выбрасывает IllegalArgumentException с generic сообщением "Invalid credentials"
     *         (не разрешаем отличать "юзер не найден" от "пароль неверный" — защита от перебора)
     * 
     * @param email адрес пользователя
     * @param password пароль в открытом виде
     * @throws IllegalArgumentException если credentials неверны
     */
    public void login(String email, String password) {
        log.info("Login attempt for email: {}", email);

        // Хардкод demo пользователя для простоты разработки/тестирования
        if ("operator@smartfreight.com".equals(email) && "demo123".equals(password)) {
            log.info("Demo user authenticated");
            return;
        }

        // Ищем реального пользователя в БД
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("User not found: {}", email);
                    // Generic сообщение об ошибке — не раскрываем существование/отсутствие юзера
                    return new IllegalArgumentException("Invalid credentials");
                });

        // Проверяем пароль: BCrypt.matches(открытыйПароль, хэш из БД)
        // matches() безопасен от timing attacks (постоянное время)
        if (!passwordEncoder.matches(password, user.getPassword())) {
            log.warn("Password mismatch for user: {}", email);
            throw new IllegalArgumentException("Invalid credentials");
        }

        log.info("Authentication successful for: {}", email);
    }
}
