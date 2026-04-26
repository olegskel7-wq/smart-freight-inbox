package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// @Repository — Spring DAO компонент для работы с EmailTicket
// JpaRepository — Spring Data JPA предоставляет готовые методы CRUD (Create, Read, Update, Delete)
// и позволяет определять собственные запросы
@Repository
public interface EmailTicketRepository extends JpaRepository<EmailTicket, Long> {

    // Найти тикет по уникальному Message-ID письма
    // Используется для проверки дедупликации: есть ли это письмо уже в БД?
    Optional<EmailTicket> findByMessageId(String messageId);

    // Найти все тикеты с конкретным статусом
    // Пример использования: найти все NEW тикеты для обработки
    List<EmailTicket> findByStatus(TicketStatus status);

    // То же самое, но с явной сортировкой — нужно UI для одновременного фильтра+сортировки.
    List<EmailTicket> findByStatus(TicketStatus status, Sort sort);

    // Найти тикеты со статусом RETRY_LATER, которые не превысили лимит попыток
    // RetryScheduler использует это для поиска тикетов к повторной обработке
    // Параметр maxRetries предотвращает бесконечные циклы повторов
    List<EmailTicket> findByStatusAndRetryCountLessThan(TicketStatus status, int maxRetries);

    /**
     * Получить ТОЛЬКО идентификаторы тикетов с указанными статусами.
     *
     * Используется AdminController.reprocessAll для построения списка задач без
     * полной загрузки сущностей в память. Преимущества проекции по id:
     *   - не тянем тяжёлые TEXT-поля body / draft_reply (могут быть до 10 МБ);
     *   - не создаём managed-сущностей в Persistence Context, которые не нужны;
     *   - последующий process(id) сам перечитает тикет в своей транзакции
     *     (паттерн "pass id, not entity").
     *
     * @param statuses коллекция искомых статусов (например NEW, RETRY_LATER)
     * @return список id, отсортированный по возрастанию (для воспроизводимости)
     */
    @Query("SELECT t.id FROM EmailTicket t WHERE t.status IN :statuses ORDER BY t.id")
    List<Long> findIdsByStatusIn(@Param("statuses") Collection<TicketStatus> statuses);

    /**
     * Bulk-сброс всех FAILED тикетов в состояние NEW (одна SQL-операция).
     *
     * Используется AdminController.reprocessAll перед запуском пайплайна:
     * FAILED-тикеты помечаются как NEW и счётчик retry_count обнуляется,
     * чтобы TicketProcessingService.process() их принял (он работает только
     * с NEW и RETRY_LATER).
     *
     * Зачем bulk вместо findAll + setStatus + save:
     *   - 1 SQL UPDATE против N SELECT + N UPDATE;
     *   - не загружаем сущности в Persistence Context (память + GC);
     *   - короткая транзакция, соединение пула освобождается мгновенно.
     *
     * Внимание: bulk-update НЕ синхронизирует Persistence Context.
     * Если в той же транзакции вы только что читали эти тикеты как managed —
     * Hibernate этого не увидит. У нас вызывающий код тикеты не читал, поэтому ОК.
     *
     * @return количество обновлённых строк
     */
    @Modifying
    @Query("UPDATE EmailTicket t " +
           "   SET t.status = com.smartfreight.inbox.entity.enums.TicketStatus.NEW, " +
           "       t.retryCount = 0, " +
           "       t.updatedAt = CURRENT_TIMESTAMP " +
           " WHERE t.status = com.smartfreight.inbox.entity.enums.TicketStatus.FAILED")
    int resetFailedToNew();
}
