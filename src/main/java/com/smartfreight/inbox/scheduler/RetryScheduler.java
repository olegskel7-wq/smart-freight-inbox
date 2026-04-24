package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Планировщик повторной обработки застрявших тикетов.
 * 
 * Запускается периодически (каждые 2 минуты) для поиска тикетов со статусом RETRY_LATER.
 * Используется для:
 * - Автоматического повтора при временных ошибках Ollama
 * - Обработки тикетов, отклонённых оператором (RETRY_LATER статус)
 * 
 * Поток:
 * 1. Ищем все тикеты RETRY_LATER с retryCount < MAX_RETRIES (5)
 * 2. Для каждого: увеличиваем retryCount, сбрасываем статус в NEW
 * 3. Запускаем TicketProcessingService.process() синхронно
 * 4. Если успех → DRAFT_READY, если ошибка → снова RETRY_LATER или FAILED
 * 
 * Защита: MAX_RETRIES предотвращает бесконечные циклы "сломанных" тикетов.
 */
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    // Максимум попыток обработки одного тикета — после этого оставляем его навсегда в RETRY_LATER
    // (оператор должен вручную разобраться — есть ли проблема в логике или данных письма)
    private static final int MAX_RETRIES = 5;

    private final EmailTicketRepository emailTicketRepository;
    private final TicketProcessingService ticketProcessingService;
    private final Counter retryCounter;

    public RetryScheduler(EmailTicketRepository emailTicketRepository,
                          TicketProcessingService ticketProcessingService,
                          MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketProcessingService = ticketProcessingService;
        this.retryCounter = Counter.builder("smartfreight_tickets_retry_count_total")
                .description("Общее количество повторных попыток обработки тикетов")
                .register(meterRegistry);
    }

    /**
     * Периодическая задача переобработки застрявших тикетов.
     * 
     * Расписание:
     * - fixedDelay = 120_000 ms (2 минуты) — пауза МЕЖДУ окончанием одного запуска и началом следующего
     * - initialDelay = 60_000 ms (1 минута) — пауза перед ПЕРВЫМ запуском (даёт приложению время инициализироваться)
     * 
     * Используем fixedDelay вместо fixedRate чтобы не накапливать параллельные запуски:
     * - fixedRate: запуск каждые 2 минуты, независимо от длительности метода
     * - fixedDelay: если метод выполнился за 5 минут, следующий запуск будет через 2 минуты после конца
     * 
     * На практике TicketProcessingService вызывает Ollama (150сек таймаут),
     * поэтому fixedDelay лучше подходит для предотвращения гонок.
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    public void retryFailedTickets() {
        // Ищем тикеты со статусом RETRY_LATER которые не превысили лимит попыток
        List<EmailTicket> toRetry = emailTicketRepository
                .findByStatusAndRetryCountLessThan(TicketStatus.RETRY_LATER, MAX_RETRIES);

        if (toRetry.isEmpty()) {
            // Нет работы — выходим тихо (не логируем каждый цикл)
            return;
        }

        log.info("RetryScheduler: found {} ticket(s) to retry", toRetry.size());

        for (EmailTicket ticket : toRetry) {
            // Увеличиваем счётчик попыток перед запуском (для логирования и защиты от зацикливания)
            ticket.setRetryCount(ticket.getRetryCount() + 1);
            // Сбрасываем статус на NEW чтобы TicketProcessingService принял тикет
            // (логика в TicketProcessingService.process() требует NEW или RETRY_LATER)
            ticket.setStatus(TicketStatus.NEW);
            emailTicketRepository.save(ticket);

            log.info("Retrying ticket ID={} (attempt {} of {})",
                    ticket.getId(), ticket.getRetryCount(), MAX_RETRIES);

            retryCounter.increment();
            // Синхронно запускаем процесс (в текущем потоке, не в отдельном потоке)
            // @Transactional в process() создаёт новую транзакцию
            ticketProcessingService.process(ticket.getId());
        }
    }
}
