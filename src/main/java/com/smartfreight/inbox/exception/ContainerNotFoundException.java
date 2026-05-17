package com.smartfreight.inbox.exception;

/**
 * Exception thrown when a container is not found in the port-service system.
 *
 * {@link com.smartfreight.inbox.exception.GlobalExceptionHandler} converts it
 * into an HTTP 404 Not Found with a JSON response {@code {"error":"CONTAINER_NOT_FOUND"}}.
 */
public class ContainerNotFoundException extends RuntimeException {

    /**
     * @param message message with the container number, e.g. "Container MSCU1234567 not found"
     */
    public ContainerNotFoundException(String message) {
        super(message);
    }

    /**
     * @param message message with the container number
     * @param cause   original exception (HTTP error, DB error, etc.)
     */
    public ContainerNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
