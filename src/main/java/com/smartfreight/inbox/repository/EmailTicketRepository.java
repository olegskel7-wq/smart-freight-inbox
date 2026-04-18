package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
