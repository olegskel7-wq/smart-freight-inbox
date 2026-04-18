package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// Планировщик повторной обработки тикетов со статусом RETRY_LATER.
// Периодически ищет «застрявшие» тикеты и снова запускает конвейер в том же потоке планировщика.
// Сценарии появления RETRY_LATER:
//   - оператор отклонил черновик (reject)
//   - временная ошибка пайплайна (при расширении логики)
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    // Максимум попыток обработки одного тикета — после этого оставляем его RETRY_LATER
    // (предотвращает бесконечный цикл для «сломанных» тикетов)
    private static final int MAX_RETRIES = 5;

    private final EmailTicketRepository emailTicketRepository;
    private final TicketProcessingService ticketProcessingService;

    public RetryScheduler(EmailTicketRepository emailTicketRepository,
                          TicketProcessingService ticketProcessingService) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketProcessingService = ticketProcessingService;
    }

    // Запускается каждые 2 минуты.
    // fixedDelay = пауза между ОКОНЧАНИЕМ предыдущего запуска и НАЧАЛОМ следующего.
    // (используем fixedDelay а не fixedRate, чтобы не накапливать параллельные запуски)
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    public void retryFailedTickets() {
        // Ищем тикеты с RETRY_LATER у которых ещё не исчерпан лимит попыток
        List<EmailTicket> toRetry = emailTicketRepository
                .findByStatusAndRetryCountLessThan(TicketStatus.RETRY_LATER, MAX_RETRIES);

        if (toRetry.isEmpty()) {
            return; // Нет тикетов для повтора — тихо выходим
        }

        log.info("RetryScheduler: found {} ticket(s) to retry", toRetry.size());

        for (EmailTicket ticket : toRetry) {
            // Увеличиваем счётчик попыток и сохраняем — до синхронного process()
            ticket.setRetryCount(ticket.getRetryCount() + 1);
            // Сбрасываем статус на NEW чтобы TicketProcessingService принял тикет
            ticket.setStatus(TicketStatus.NEW);
            emailTicketRepository.save(ticket);

            log.info("Retrying ticket ID={} (attempt {} of {})",
                    ticket.getId(), ticket.getRetryCount(), MAX_RETRIES);

            ticketProcessingService.process(ticket.getId());
        }
    }
}
