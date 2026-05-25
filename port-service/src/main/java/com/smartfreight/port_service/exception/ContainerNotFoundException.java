package com.smartfreight.port_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a container cannot be found for the requested number.
 *
 * Mapped to HTTP 404 via {@link ResponseStatus} (used as a fallback when no
 * {@code @RestControllerAdvice} is registered, e.g. in standalone MockMvc tests)
 * and enriched with a {@code ProblemDetail} body by
 * {@link GlobalExceptionHandler} in the running application.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ContainerNotFoundException extends RuntimeException {

    public ContainerNotFoundException(String containerNumber) {
        super("Container not found: " + containerNumber);
    }
}
