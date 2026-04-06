package com.smartfreight.inbox.config;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

// @Configuration — этот класс содержит настройки Spring (бины/компоненты)
@Configuration
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    // Репозиторий нужен для работы с тикетами при отклонении задач из очереди
    private final EmailTicketRepository emailTicketRepository;

    // Constructor Injection — Spring автоматически передаёт репозиторий сюда
    public AsyncConfig(EmailTicketRepository emailTicketRepository) {
        this.emailTicketRepository = emailTicketRepository;
    }

    // @Bean — Spring создаёт этот объект и управляет им
    // name = "llmTaskExecutor" — имя, по которому @Async найдёт именно этот пул потоков
    @Bean(name = "llmTaskExecutor")
    public Executor llmTaskExecutor() {
        // ThreadPoolTaskExecutor — пул потоков для параллельного выполнения задач
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Постоянно активных потоков: 4
        // Логика: каждый тикет = 2 LLM вызова (Qwen извлечение + Llama черновик).
        // Ollama с OLLAMA_NUM_PARALLEL=2 обрабатывает 2 запроса одновременно.
        // 4 потока = 2 активных вывода + 2 в буфере пока GPU заканчивает предыдущие.
        // ВАЖНО: потоки не нагружают CPU — они просто ждут HTTP ответа от Ollama.
        // Иметь 8-16 ожидающих потоков при GPU-параллелизме=2 бессмысленно.
        executor.setCorePoolSize(4);

        // Максимум потоков при пиковой нагрузке (burst): 8
        // RTX 5080 16GB VRAM — GPU является узким местом, не количество потоков.
        // Более 8 потоков просто создадут лишнюю очередь внутри Ollama.
        executor.setMaxPoolSize(8);

        // Очередь ожидания: до 50 задач ждут свободного потока
        // Если очередь переполнена — срабатывает RejectedExecutionHandler ниже
        executor.setQueueCapacity(50);

        // Префикс имени потока — видно в логах: "LLM-Async-1", "LLM-Async-2"
        // Помогает отличить эти потоки от других в логах
        executor.setThreadNamePrefix("LLM-Async-");

        // MdcTaskDecorator — переносит контекст логирования (ticketId, messageId)
        // из основного потока в новый async-поток (см. класс ниже)
        executor.setTaskDecorator(new MdcTaskDecorator());

        // Обработчик переполнения очереди — вместо исключения помечает тикет RETRY_LATER
        executor.setRejectedExecutionHandler(new EmailTicketRejectedExecutionHandler(emailTicketRepository));

        // Применяет все настройки выше
        executor.initialize();
        return executor;
    }

    // -----------------------------------------------------------------------
    // MDC = Mapped Diagnostic Context — это "карта контекста" для логирования
    // Проблема: когда @Async создаёт новый поток, переменные (ticketId, messageId)
    // из старого потока туда НЕ переносятся автоматически.
    // Решение: TaskDecorator "оборачивает" задачу и копирует MDC в новый поток.
    // -----------------------------------------------------------------------
    public static class MdcTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            // Снимаем копию MDC из потока, который отправляет задачу
            Map<String, String> contextMap = MDC.getCopyOfContextMap();

            // Возвращаем новую задачу, которая сначала восстанавливает MDC
            return () -> {
                try {
                    // Если контекст был — восстанавливаем его в новом потоке
                    if (contextMap != null) {
                        MDC.setContextMap(contextMap);
                    }
                    // Выполняем оригинальную задачу (обработку тикета)
                    runnable.run();
                } finally {
                    // Обязательно очищаем MDC после завершения — потоки повторно используются
                    MDC.clear();
                }
            };
        }
    }

    // -----------------------------------------------------------------------
    // Что происходит когда очередь на 50 задач переполнена?
    // По умолчанию Spring бросает исключение и задача теряется.
    // Наш обработчик вместо этого помечает тикет как RETRY_LATER —
    // RetryScheduler позже подберёт его и повторит обработку.
    // -----------------------------------------------------------------------
    public static class EmailTicketRejectedExecutionHandler implements RejectedExecutionHandler {
        private final EmailTicketRepository emailTicketRepository;

        public EmailTicketRejectedExecutionHandler(EmailTicketRepository emailTicketRepository) {
            this.emailTicketRepository = emailTicketRepository;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            // Проверяем: задача реализует наш интерфейс (содержит ID тикета)?
            if (r instanceof TicketProcessingRunnable task) {
                log.warn("LLM executor queue full. Marking ticket {} as RETRY_LATER", task.getTicketId());

                // Находим тикет в БД и переводим его в статус RETRY_LATER
                emailTicketRepository.findById(task.getTicketId()).ifPresent(ticket -> {
                    ticket.setStatus(TicketStatus.RETRY_LATER);
                    emailTicketRepository.save(ticket);
                });
            }
        }
    }

    // Интерфейс-маркер: задача обработки тикета должна уметь возвращать свой ticketId
    // Это нужно обработчику выше, чтобы знать какой тикет пометить RETRY_LATER
    public interface TicketProcessingRunnable extends Runnable {
        Long getTicketId();
    }
}
