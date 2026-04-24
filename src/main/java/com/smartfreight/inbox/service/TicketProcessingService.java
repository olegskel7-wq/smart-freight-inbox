package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Container;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

// Оркестратор AI-конвейера обработки тикетов.
// Координирует три шага: извлечение номера (Qwen) → поиск в БД → черновик ответа (Llama).
// Выполняется синхронно в потоке вызывающего (EmailIngestionService / RetryScheduler).
@Service
public class TicketProcessingService {

    private static final Logger log = LoggerFactory.getLogger(TicketProcessingService.class);

    // Максимум 3 попытки при конфликте оптимистической блокировки
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final EmailTicketRepository emailTicketRepository;
    private final AiExtractionService aiExtractionService;
    private final ContainerLookupService containerLookupService;
    private final AiDraftingService aiDraftingService;
    private final Timer processingTimer;
    private final Counter draftReadyCounter;
    private final Counter failedCounter;

    public TicketProcessingService(EmailTicketRepository emailTicketRepository,
                                   AiExtractionService aiExtractionService,
                                   ContainerLookupService containerLookupService,
                                   AiDraftingService aiDraftingService,
                                   MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.aiExtractionService = aiExtractionService;
        this.containerLookupService = containerLookupService;
        this.aiDraftingService = aiDraftingService;
        this.processingTimer = Timer.builder("smartfreight_processing_duration_seconds")
                .description("Время обработки тикета AI-конвейером")
                .register(meterRegistry);
        this.draftReadyCounter = Counter.builder("smartfreight_tickets_status_total")
                .tag("status", "DRAFT_READY")
                .description("Количество тикетов по статусу")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("smartfreight_tickets_status_total")
                .tag("status", "FAILED")
                .description("Количество тикетов по статусу")
                .register(meterRegistry);
    }

    // @Transactional(propagation = REQUIRES_NEW) — создаёт новую транзакцию для каждого вызова.
    // Необходимо потому что метод вызывается из afterCommit() callback-а, когда текущая транзакция уже закрыта.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long ticketId) {
        MDC.put("ticketId", String.valueOf(ticketId));
        long startTime = System.nanoTime();
        try {
            log.info("Starting AI processing");

            // Цикл retry на случай оптимистической блокировки (конфликт версий в БД)
            for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
                try {
                    // ПАТТЕРН "Pass ID, not entity": перезагружаем тикет внутри новой транзакции.
                    // Переданный объект был бы в detached-состоянии и вызвал LazyInitializationException.
                    EmailTicket ticket = emailTicketRepository.findById(ticketId)
                            .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));

                    // Защита от двойной обработки: принимаем только NEW и RETRY_LATER
                    if (ticket.getStatus() != TicketStatus.NEW && ticket.getStatus() != TicketStatus.RETRY_LATER) {
                        log.warn("Ticket already in state {}, skipping", ticket.getStatus());
                        return;
                    }

                    // Захватываем тикет — переводим в PROCESSING.
                    // saveAndFlush сразу виден другим потокам (не ждёт конца транзакции).
                    ticket.setStatus(TicketStatus.PROCESSING);
                    ticket.setUpdatedAt(LocalDateTime.now());
                    emailTicketRepository.saveAndFlush(ticket);

                    // ── Шаг 1: Извлечение номера контейнера ──────────────────────────────
                    // Qwen 2.5 анализирует тело письма и находит номер по формату ISO 6346.
                    // Если Ollama недоступен — используется regex-заглушка (см. AiExtractionService).
                    log.info("Step 1/3: Extracting container number via Qwen 2.5");
                    String containerNumber = aiExtractionService.extractContainerNumber(ticket.getBody());
                    ticket.setExtractedContainerNumber(containerNumber.isEmpty() ? null : containerNumber);
                    ticket.setUpdatedAt(LocalDateTime.now());
                    emailTicketRepository.save(ticket);

                    // ── Шаг 2: Поиск контейнера в базе данных ────────────────────────────
                    // Ищем контейнер по извлечённому номеру. Если не найден — передаём null в drafting.
                    log.info("Step 2/3: Looking up container '{}' in database", containerNumber);
                    Optional<Container> containerOpt = containerNumber.isEmpty()
                            ? Optional.empty()
                            : containerLookupService.findByNumber(containerNumber);

                    Container container = containerOpt.orElse(null);
                    if (container == null) {
                        log.warn("Container '{}' not found — draft will reflect missing data", containerNumber);
                    }

                    // ── Шаг 3: Генерация черновика ответа ────────────────────────────────
                    // Llama 3.1 формирует профессиональный ответ на основе письма + данных контейнера.
                    // Если Ollama недоступен — возвращается статичный fallback-черновик.
                    log.info("Step 3/3: Generating reply draft via Llama 3.1");
                    String draft = aiDraftingService.generateDraft(ticket.getBody(), container);
                    ticket.setDraftReply(draft);

                    // ── Финал: сохраняем результат ────────────────────────────────────────
                    ticket.setStatus(TicketStatus.DRAFT_READY);
                    ticket.setUpdatedAt(LocalDateTime.now());
                    emailTicketRepository.save(ticket);

                    draftReadyCounter.increment();
                    processingTimer.record(System.nanoTime() - startTime, java.util.concurrent.TimeUnit.NANOSECONDS);
                    log.info("Ticket processing completed: DRAFT_READY");
                    return; // Успех — выходим из цикла retry

                } catch (ObjectOptimisticLockingFailureException e) {
                    // Конфликт версий: другой поток успел изменить запись.
                    // Hibernate обнаружил несоответствие версии — делаем retry.
                    log.warn("Optimistic lock conflict on attempt {}/{}, retrying", attempt + 1, MAX_RETRY_ATTEMPTS);
                    if (attempt == MAX_RETRY_ATTEMPTS - 1) {
                        log.error("All retry attempts exhausted — marking ticket as FAILED");
                        markFailed(ticketId);
                    }
                } catch (Exception e) {
                    // Неожиданная ошибка (AI недоступен, сетевые проблемы и т.д.)
                    log.error("Unexpected error during ticket processing", e);
                    markFailed(ticketId);
                    return;
                }
            }
        } finally {
            MDC.remove("ticketId");
        }
    }

    // Переводит тикет в статус FAILED — повторная обработка невозможна до ручного вмешательства.
    private void markFailed(Long ticketId) {
        emailTicketRepository.findById(ticketId).ifPresent(t -> {
            t.setStatus(TicketStatus.FAILED);
            t.setUpdatedAt(LocalDateTime.now());
            emailTicketRepository.save(t);
            failedCounter.increment();
        });
    }
}
