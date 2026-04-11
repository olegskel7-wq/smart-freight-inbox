package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// REST контроллер для аутентификации через API.
// Используется при работе с клиентами вне браузера (Postman, мобильное приложение, v2 фронтенд).
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // POST /api/auth/login — получить JWT токен по email и паролю.
    // Запрос: { "email": "...", "password": "..." }
    // Ответ:  { "token": "eyJhbGci..." }
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest request) {
        try {
            String token = authService.login(request.email(), request.password());
            return ResponseEntity.ok(Map.of("token", token));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid email or password"));
        }
    }

    // DTO для тела запроса (Java 16+ record — компактный способ задать immutable объект)
    public record LoginRequest(String email, String password) {}
}
