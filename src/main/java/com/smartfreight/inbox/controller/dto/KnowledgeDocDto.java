package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.entity.KnowledgeDoc;

import java.time.LocalDateTime;

/**
 * One knowledge-base document for the React {@code /knowledge} page (Phase 3.9).
 *
 * <p>Unlike {@link TrainingExampleDto} the full {@link #content} is shipped, not a preview: knowledge
 * documents are short operator-authored text (FAQ answers / policies), and the page lets the operator
 * edit them inline, so it needs the whole text without a second fetch. The table truncates for display
 * client-side.
 */
public record KnowledgeDocDto(
        Long id,
        String title,
        String content,
        String source,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static KnowledgeDocDto from(KnowledgeDoc d) {
        return new KnowledgeDocDto(
                d.getId(),
                d.getTitle(),
                d.getContent(),
                d.getSource(),
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }
}
