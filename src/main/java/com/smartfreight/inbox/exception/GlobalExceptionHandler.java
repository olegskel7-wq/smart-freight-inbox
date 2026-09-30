package com.smartfreight.inbox.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for REST controllers.
 *
 * Applied only to {@code @RestController} classes (AdminController) —
 * it does not apply to the Thymeleaf controller ({@code @Controller}),
 * which receives an HTML error page via Spring Boot BasicErrorController.
 */
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    /**
     * Tracked item not found → 404 Not Found.
     *
     * PortServiceItemLookup currently returns Optional.empty() instead of throwing,
     * but the handler is kept as a safety net.
     */
    @ExceptionHandler(ItemNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleItemNotFound(ItemNotFoundException ex) {
        Map<String, String> response = new HashMap<>();
        response.put("error", "ITEM_NOT_FOUND");
        response.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Invalid input parameter → 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        Map<String, String> response = new HashMap<>();
        response.put("error", "INVALID_ARGUMENT");
        response.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * All other unexpected exceptions → 500 Internal Server Error.
     * The client receives a generic message — internal details are not exposed.
     *
     * <p>Exceptions that already carry an intended HTTP status — {@code ResponseStatusException}
     * (thrown by every {@code /api} controller for its 400/404/409s) and the Spring MVC exceptions
     * implementing {@link ErrorResponse} (wrong method, unsupported media type, …) — keep that status
     * and their deliberately client-facing reason. Without this, the catch-all turned all of them
     * into a 500 "An unexpected error occurred" and the SPA never saw why a save was rejected.
     * (Unreadable JSON / type mismatches don't implement {@code ErrorResponse} and still land here.)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            String detail = errorResponse.getBody().getDetail();
            Map<String, String> response = new HashMap<>();
            // Same shape as the handlers above: an upper-case code, e.g. CONFLICT / NOT_FOUND.
            response.put("error", known != null ? known.name() : String.valueOf(status.value()));
            response.put("message", detail != null ? detail : "Request failed");
            return ResponseEntity.status(status).headers(errorResponse.getHeaders()).body(response);
        }
        Map<String, String> response = new HashMap<>();
        response.put("error", "INTERNAL_SERVER_ERROR");
        response.put("message", "An unexpected error occurred");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
