package com.smartfreight.inbox.controller.dto;

import java.util.List;

/**
 * Result of a CSV rate-sheet upload (Phase 3.2): the per-row counts and any skipped-row messages the
 * operator must see, plus the {@code snapshot} of the refreshed table so the SPA re-renders in one
 * round trip (mirrors the "mutation returns refreshed state" pattern of the settings API).
 */
public record RateUploadResponse(
        int inserted,
        int updated,
        int total,
        List<String> errors,
        RatesResponse snapshot
) {
}
