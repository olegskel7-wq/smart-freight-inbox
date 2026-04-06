package com.smartfreight.inbox.entity;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

// @Entity — говорит Spring/Hibernate: этот класс = таблица в базе данных
@Entity

// @Table — указывает имя таблицы и дополнительные ограничения
// uniqueConstraints — запрещает два письма с одинаковым Message-ID (дедупликация)
@Table(
    name = "email_ticket",
    uniqueConstraints = @UniqueConstraint(columnNames = "message_id")
)

// Lombok: автоматически генерирует геттеры (getStatus(), getId(), ...)
@Getter
// Lombok: автоматически генерирует сеттеры (setStatus(), setBody(), ...)
@Setter
// Lombok: генерирует конструктор без аргументов — требуется JPA
@NoArgsConstructor
// Lombok: генерирует конструктор со всеми аргументами
@AllArgsConstructor
// Lombok: паттерн Builder — позволяет создавать объект цепочкой:
// EmailTicket.builder().messageId("...").subject("...").build()
@Builder

// GDPR — "мягкое удаление": вместо DELETE выполняет UPDATE SET deleted=true
// Версия нужна для оптимистической блокировки (защита от race condition)
@SQLDelete(sql = "UPDATE email_ticket SET deleted = true WHERE id=? AND version=?")

// Фильтр: при любом SELECT автоматически добавляет WHERE deleted=false
// Пользователь никогда не увидит "удалённые" записи, но они остаются в БД
@SQLRestriction("deleted=false")
public class EmailTicket {

    // Первичный ключ. IDENTITY = PostgreSQL сам генерирует следующий ID (1, 2, 3...)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Уникальный идентификатор письма из заголовка "Message-ID"
    // Используется для дедупликации — не сохраняем одно письмо дважды
    @Column(name = "message_id", nullable = false, unique = true)
    private String messageId;

    // Email адрес отправителя (например: customer@company.com)
    @Column(name = "sender_email")
    private String senderEmail;

    // Тема письма (Subject)
    @Column(name = "subject")
    private String subject;

    // Тело письма. TEXT в SQL — неограниченная длина (в отличие от VARCHAR)
    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    // Номер контейнера, который AI извлечёт из тела письма (например: MSCU1234567)
    @Column(name = "extracted_container_number")
    private String extractedContainerNumber;

    // @Enumerated(EnumType.STRING) — хранит enum как строку "NEW", не как число 0
    // Это важно: если добавить новый статус — старые записи не сломаются
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    // @Builder.Default — при использовании Builder это поле получает значение NEW
    @Builder.Default
    private TicketStatus status = TicketStatus.NEW;

    // Черновик ответа, который сгенерирует AI (Llama 3.1)
    // Оператор просмотрит и отправит его клиенту
    @Column(name = "draft_reply", columnDefinition = "TEXT")
    private String draftReply;

    // Счётчик попыток повторной обработки при ошибках
    // Если AI временно недоступен — тикет получает статус RETRY_LATER
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    // Флаг "мягкого удаления". false = запись активна, true = "удалена" (GDPR)
    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    // Время создания тикета. updatable=false — никогда не меняется после записи
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    // Время последнего изменения тикета (обновляется при каждом save)
    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    // Оптимистическая блокировка: Hibernate автоматически увеличивает это число
    // при каждом UPDATE. Если два потока читают version=5 и оба пытаются сохранить —
    // второй получит ObjectOptimisticLockingFailureException (защита от race condition)
    @Version
    private Integer version;
}
