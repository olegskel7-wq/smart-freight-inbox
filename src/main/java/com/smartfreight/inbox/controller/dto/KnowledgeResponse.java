package com.smartfreight.inbox.controller.dto;

import java.util.List;

/**
 * Snapshot of the current tenant's knowledge base for the React {@code /knowledge} page (Phase 3.9).
 * {@code featureEnabled} mirrors {@code feature.knowledge-base}: when off, knowledge is no longer
 * injected into drafts, so the SPA shows a warning badge (existing documents still list/edit).
 */
public record KnowledgeResponse(boolean featureEnabled, List<KnowledgeDocDto> documents) {
}
