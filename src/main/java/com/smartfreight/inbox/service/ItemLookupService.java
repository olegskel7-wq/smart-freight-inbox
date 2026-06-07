package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TrackedItem;

import java.util.Optional;

/**
 * Vertical-neutral lookup of a tracked item by its key identifier.
 *
 * <p>The AI pipeline ({@link TicketProcessingService}) depends on this abstraction rather than a
 * concrete backend, so a new vertical can plug in a different data source (a real TMS, a CRM, a
 * case-management system) without changing the orchestrator. The freight implementation
 * ({@link PortServiceItemLookup}) talks to the external port-service over HTTP.
 *
 * <p>Implementations must be resilient: when the backend is unavailable or the item is not found,
 * return {@link Optional#empty()} rather than throwing, so the pipeline can still draft a reply
 * that reflects the missing data.
 *
 */
public interface ItemLookupService {

    /**
     * Looks up a tracked item by the identifier extracted from the customer email.
     *
     * @param identifier the key identifier (e.g. an ISO 6346 container number); may be null/blank
     * @return the item data if found, or an empty Optional
     */
    Optional<TrackedItem> findByIdentifier(String identifier);
}
