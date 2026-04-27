package com.smartfreight.inbox.exception;

/**
 * Išimtis, metama kai konteineris nerastas port-service sistemoje.
 *
 * {@link com.smartfreight.inbox.exception.GlobalExceptionHandler} ją paverčia
 * į HTTP 404 Not Found su JSON atsaku {@code {"error":"CONTAINER_NOT_FOUND"}}.
 */
public class ContainerNotFoundException extends RuntimeException {

    /**
     * @param message pranešimas su konteinerio numeriu, pvz.: "Container MSCU1234567 not found"
     */
    public ContainerNotFoundException(String message) {
        super(message);
    }

    /**
     * @param message pranešimas su konteinerio numeriu
     * @param cause   pradinė išimtis (HTTP klaida, DB klaida ir pan.)
     */
    public ContainerNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
