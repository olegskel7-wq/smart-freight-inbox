package com.smartfreight.inbox.controller.dto;

import java.util.List;

/**
 * Snapshot of the current tenant's captured corrections for the React {@code /training-examples}
 * page (Phase 3.2). {@code featureEnabled} mirrors {@code feature.ai-feedback-loop}: when off,
 * edits are no longer captured, so the SPA shows a warning badge (existing examples still list).
 */
public record TrainingExamplesResponse(boolean featureEnabled, List<TrainingExampleDto> examples) {
}
