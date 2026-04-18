package com.smartfreight.inbox.entity;

import lombok.*;

import java.time.LocalDateTime;

// Простой POJO (не JPA Entity) — данные о контейнере получаются из внешнего port-service.
// Используется в AiDraftingService для формирования промпта для LLM.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Container {

    private String containerNumber;
    private String status;
    private LocalDateTime eta;
    private String location;
    private LocalDateTime updatedAt;
}
