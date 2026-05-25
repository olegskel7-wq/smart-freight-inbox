package com.smartfreight.port_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralised REST error handling.
 *
 * Converts domain exceptions into RFC 7807 {@link ProblemDetail} responses so
 * that clients (the inbox service) receive a structured, machine-readable body
 * instead of an empty error response.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Maps {@link ContainerNotFoundException} to a 404 response with a problem body:
     * <pre>
     * {
     *   "type": "about:blank",
     *   "title": "Container Not Found",
     *   "status": 404,
     *   "detail": "Container not found: XXXX0000000"
     * }
     * </pre>
     */
    @ExceptionHandler(ContainerNotFoundException.class)
    public ProblemDetail handleContainerNotFound(ContainerNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Container Not Found");
        return problem;
    }
}
