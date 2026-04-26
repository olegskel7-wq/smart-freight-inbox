package com.smartfreight.inbox.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

// @RestControllerAdvice — глобальный обработчик исключений для REST-контроллеров.
//
// Параметр annotations = RestController.class явно ограничивает scope:
// этот advice применяется ТОЛЬКО к классам, помеченным @RestController
// (TicketController, AuthController, AdminController), и НЕ применяется к
// Thymeleaf-контроллерам, помеченным @Controller (TicketViewController).
//
// Зачем это нужно:
//   - REST-клиенты должны получать JSON: { "error": "...", "message": "..." }
//   - UI-пользователи должны получать HTML-страницу ошибки (templates/error.html),
//     которую за нас рендерит Spring Boot BasicErrorController.
// Если бы advice был "глобальным" без scope — UI-пользователь увидел бы JSON
// в браузере при выбросе IllegalArgumentException, что нежелательно.
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    // Обработчик для контейнера не найдена
    @ExceptionHandler(ContainerNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleContainerNotFound(ContainerNotFoundException ex) {
        Map<String, String> response = new HashMap<>();
        response.put("error", "CONTAINER_NOT_FOUND");
        response.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    // Обработчик для ошибок валидации (неправильные параметры)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        Map<String, String> response = new HashMap<>();
        response.put("error", "INVALID_ARGUMENT");
        response.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // Обработчик для всех остальных исключений
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        Map<String, String> response = new HashMap<>();
        response.put("error", "INTERNAL_SERVER_ERROR");
        response.put("message", "An unexpected error occurred");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
