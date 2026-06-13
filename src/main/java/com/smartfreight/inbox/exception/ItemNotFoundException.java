package com.smartfreight.inbox.exception;

/**
 * Exception thrown when a tracked item is not found in the backend lookup service.
 *
 * Vertical-neutral (Phase 0 universalisation): the reference backend is port-service
 * (containers), but a different vertical's {@code ItemLookupService} would throw the
 * same exception for its own missing entity.
 *
 * {@link com.smartfreight.inbox.exception.GlobalExceptionHandler} converts it
 * into an HTTP 404 Not Found with a JSON response {@code {"error":"ITEM_NOT_FOUND"}}.
 */
public class ItemNotFoundException extends RuntimeException {

    /**
     * @param message message with the item identifier, e.g. "Item MSCU1234567 not found"
     */
    public ItemNotFoundException(String message) {
        super(message);
    }

    /**
     * @param message message with the item identifier
     * @param cause   original exception (HTTP error, DB error, etc.)
     */
    public ItemNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
