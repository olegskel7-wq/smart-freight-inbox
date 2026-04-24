package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST API контроллер для аутентификации пользователей.
 * 
 * Используется клиентами (Postman, мобильные приложения, frontend v2) для получения
 * доступа к защищённым эндпоинтам без использования форм Spring Security.
 * 
 * Браузерные клиенты используют TicketViewController + form login (сессия в cookie).
 * API клиенты используют этот контроллер + JWT/OAuth2 (при расширении).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * POST /api/auth/login — проверить email + пароль пользователя.
     * 
     * Request:  { "email": "operator@smartfreight.com", "password": "demo123" }
     * Response: 200 OK  → { "status": "ok" }
     * Response: 401 Unauthorized → { "error": "Invalid email or password" }
     * 
     * Используется для верификации credentials перед выдачей токена.
     * На этом этапе v1 не генерируется JWT — вернём просто { status: "ok" } для тестирования.
     * 
     * @param request запрос с email и паролем
     * @return 200 OK если credentials верны, 401 если нет
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest request) {
        try {
            // Сервис выбросит IllegalArgumentException если credentials неверны
            authService.login(request.email(), request.password());
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            // Generic сообщение об ошибке (не разрешаем отличать "юзер не найден" от "пароль неверный")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid email or password"));
        }
    }

    /**
     * Record для десериализации JSON запроса аутентификации.
     * 
     * @param email адрес пользователя
     * @param password пароль в открытом виде
     */
    public record LoginRequest(String email, String password) {}
}
