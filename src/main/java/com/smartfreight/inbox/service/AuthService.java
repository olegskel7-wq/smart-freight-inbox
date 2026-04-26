package com.smartfreight.inbox.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * Сервис аутентификации пользователей для REST API ({@code /api/auth/login}).
 *
 * Делегирует всю фактическую проверку учётных данных
 * Spring-Security'му {@link AuthenticationManager}. Тот, в свою очередь,
 * использует:
 *  - {@link com.smartfreight.inbox.config.DbUserDetailsService} — поиск пользователя в БД;
 *  - {@link org.springframework.security.crypto.password.PasswordEncoder} — сравнение BCrypt-хешей.
 *
 * Преимущества по сравнению с прошлой реализацией:
 *  - удалён хардкод demo-пользователя в коде (единый источник — таблица {@code app_user});
 *  - удалена ручная проверка {@code passwordEncoder.matches(...)} (теперь делает Spring Security);
 *  - тот же путь аутентификации, что и у form login UI — проще аудит и сопровождение.
 *
 * Контракт {@link IllegalArgumentException} оставлен прежним — это позволяет
 * {@link com.smartfreight.inbox.controller.AuthController} обрабатывать ошибку
 * без изменений и возвращать клиенту унифицированное {@code 401 Unauthorized}.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * Менеджер аутентификации Spring Security. Объявлен бином в
     * {@link com.smartfreight.inbox.config.SecurityConfig}.
     */
    private final AuthenticationManager authenticationManager;

    public AuthService(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    /**
     * Проверяет учётные данные пользователя через Spring Security.
     *
     * Алгоритм:
     *  1. Создаём {@link UsernamePasswordAuthenticationToken} (ещё неаутентифицированный).
     *  2. Передаём его в {@link AuthenticationManager#authenticate(Authentication)}.
     *  3. Spring Security находит {@code DaoAuthenticationProvider}, тот вызывает
     *     {@code DbUserDetailsService.loadUserByUsername(email)} и сравнивает
     *     введённый пароль с BCrypt-хешем из БД через {@code PasswordEncoder.matches(...)}.
     *  4. При успехе возвращается аутентифицированный объект {@link Authentication}
     *     (содержит principal, authorities и т.п. — пригодится в v2 для JWT).
     *  5. При ошибке Spring Security бросает {@link AuthenticationException} —
     *     мы оборачиваем её в {@link IllegalArgumentException}, чтобы не менять
     *     внешний контракт сервиса.
     *
     * Защитные соображения:
     *  - сообщение об ошибке генерализовано ({@code "Invalid credentials"}) —
     *    клиент не должен отличать "пользователь не найден" от "пароль неверен"
     *    (защита от перебора и enumeration-атак);
     *  - в логах причина пишется детально — это видно только серверу.
     *
     * @param email    адрес пользователя (используется как логин)
     * @param password пароль в открытом виде
     * @return аутентифицированный {@link Authentication} (пригодится для JWT в v2)
     * @throws IllegalArgumentException если учётные данные неверны
     */
    public Authentication login(String email, String password) {
        log.info("Login attempt for email: {}", email);
        try {
            Authentication authenticated = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, password));
            log.info("Authentication successful for: {}", email);
            return authenticated;
        } catch (AuthenticationException e) {
            // В лог пишем причину (тип + сообщение), наружу — обобщённое сообщение.
            log.warn("Authentication failed for {}: {} ({})",
                    email, e.getMessage(), e.getClass().getSimpleName());
            throw new IllegalArgumentException("Invalid credentials");
        }
    }
}
