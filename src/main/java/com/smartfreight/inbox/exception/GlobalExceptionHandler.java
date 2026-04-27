package com.smartfreight.inbox.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Globalus išimčių tvarkytojas REST valdikliams.
 *
 * Taikomas tik {@code @RestController} klasėms (AdminController) –
 * Thymeleaf valdikliui ({@code @Controller}) čia neveikia,
 * jis gauna HTML klaidos puslapį per Spring Boot BasicErrorController.
 */
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    /**
     * Konteineris nerastas port-service sistemoje → 404 Not Found.
     */
    @ExceptionHandler(ContainerNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleContainerNotFound(ContainerNotFoundException ex) {
        Map<String, String> response = new HashMap<>();
        response.put("error", "CONTAINER_NOT_FOUND");
        response.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Netinkamas įvesties parametras → 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        Map<String, String> response = new HashMap<>();
        response.put("error", "INVALID_ARGUMENT");
        response.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Visos kitos nenumatytos išimtys → 500 Internal Server Error.
     * Klientas gauna generalizuotą pranešimą – vidinės detalės nepateikiamos.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        Map<String, String> response = new HashMap<>();
        response.put("error", "INTERNAL_SERVER_ERROR");
        response.put("message", "An unexpected error occurred");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
