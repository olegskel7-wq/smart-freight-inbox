package com.smartfreight.inbox.controller.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Stable JSON shape for a page of results (Phase 3.2 API). We map Spring Data's {@link Page} to
 * this record rather than serialising {@code PageImpl} directly — the latter's JSON is internal
 * and Spring warns against relying on it.
 */
public record PageDto<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    /** Maps a {@code Page<E>} of entities to a {@code PageDto<T>} of DTOs via {@code mapper}. */
    public static <E, T> PageDto<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageDto<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
