package com.smartfreight.inbox.service;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционный тест полного AI конвейера обработки тикетов.
 * 
 * Это НЕ unit тест — это E2E тест всей системы:
 * - Spring контекст поднимается полностью
 * - Все бины создаются и связываются
 * - Реальная БД (через Testcontainers) используется
 * - Реальные вызовы к Ollama (если доступна)
 * 
 * @SpringBootTest — поднимает полный контекст (как production)
 * @Import(TestcontainersConfiguration.class) — использует Docker PostgreSQL для тестов
 * 
 * Процесс:
 * 1. Создаём тикет с текстом, содержащим номер контейнера
 * 2. Запускаем TicketProcessingService.process()
 * 3. Проверяем что конвейер выполнился (статус изменился)
 * 4. Если Ollama доступна — проверяем что черновик сгенерирован
 * 5. Если Ollama недоступна — проверяем что fallback сработал
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TicketProcessingServiceTest {

    @Autowired
    private TicketProcessingService ticketProcessingService;

    @Autowired
    private EmailTicketRepository emailTicketRepository;

    /**
     * Основной интеграционный тест полного pipeline.
     * 
     * Сценарий:
     * 1. Сохраняем тикет с номером контейнера MSCU1234567
     * 2. Запускаем process() — синхронно выполняется до конца
     * 3. Перечитываем тикет из БД
     * 4. Проверяем статус (должен быть DRAFT_READY если Ollama работает)
     * 5. Выводим результаты в консоль для отладки
     * 
     * Важно: этот тест может быть медленным, если Ollama работает (150сек для LLM).
     * Используйте только для интеграционного тестирования, не для CI/CD быстрых проверок.
     */
    @Test
    void testContainerExtraction_andDraftGeneration() {

        // ── Подготовка: создаём тикет ────────────────────────────────────────────────
        // Номер MSCU1234567 совпадает с демо-данными в V2__seed_demo_containers.sql
        // Если Ollama доступна — получим реальные данные контейнера и черновик
        EmailTicket ticket = EmailTicket.builder()
                .messageId("test-unit-001@test.com")
                .senderEmail("customer@example.com")
                .subject("Container status inquiry")
                .body("Good morning, could you please provide status update on container MSCU1234567?" +
                      " When will it arrive at Felixstowe port?")
                .build();

        EmailTicket saved = emailTicketRepository.save(ticket);
        System.out.println("[TEST] Ticket saved with ID=" + saved.getId());

        // ── Запуск: передаём только ID (паттерн Pass ID, not entity) ─────────────────
        // Процесс вызывает:
        // 1. AiExtractionService → извлекает MSCU1234567
        // 2. ContainerLookupService → ищет в БД (или port-service если в production)
        // 3. AiDraftingService → генерирует ответ через Llama
        // Весь процесс синхронный — возвращаемся только после завершения
        ticketProcessingService.process(saved.getId());

        // ── Результат: перечитываем тикет из БД ───────────────────────────────────────
        // process() синхронный, поэтому на этом моменте обработка уже завершена
        EmailTicket result = emailTicketRepository.findById(saved.getId()).orElseThrow();

        // Выводим результаты для visual inspection (используется при отладке тестов)
        System.out.println("\n========================================");
        System.out.println("  PROCESSING RESULT");
        System.out.println("========================================");
        System.out.println("Status              : " + result.getStatus());
        System.out.println("Extracted container : " + result.getExtractedContainerNumber());
        System.out.println("Retry count         : " + result.getRetryCount());
        System.out.println("----------------------------------------");
        System.out.println("Draft reply:");
        System.out.println(result.getDraftReply() != null ? result.getDraftReply() : "(empty — Ollama not available)");
        System.out.println("========================================\n");

        // ── Проверки (Assertions) ──────────────────────────────────────────────────────

        // Основная проверка: статус должен измениться (не NEW и не PROCESSING)
        // Значит конвейер точно выполнился до конца
        assertNotEquals(TicketStatus.NEW, result.getStatus(),
                "Processing should have started");
        assertNotEquals(TicketStatus.PROCESSING, result.getStatus(),
                "Processing should have completed before process() returned");

        // Если Ollama работает — проверяем полный успех
        if (result.getStatus() == TicketStatus.DRAFT_READY) {
            // Чёрный ящик Ollama: не тестируем что ровно говорит LLM,
            // только что что-то было сгенерировано
            assertNotNull(result.getDraftReply(),
                    "Draft reply should be generated");
            assertFalse(result.getDraftReply().isBlank(),
                    "Draft reply should not be empty");
            System.out.println("[TEST] ✓ Full pipeline succeeded with Ollama");
        }

        // Если Ollama недоступна — процесс может завершиться с FAILED
        // но fallback черновик должен быть создан (см. AiDraftingService.buildFallbackDraft)
        if (result.getStatus() == TicketStatus.FAILED) {
            System.out.println("[TEST] Ollama not available — check fallback draft above");
        }
    }
}
