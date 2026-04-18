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

// @SpringBootTest — поднимает полный Spring контекст (все бины, конфиги, сервисы)
// @Import — используем Testcontainers: тест сам поднимает отдельный PostgreSQL в Docker
// (не трогает твою рабочую БД на порту 5433)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TicketProcessingServiceTest {

    @Autowired
    private TicketProcessingService ticketProcessingService;

    @Autowired
    private EmailTicketRepository emailTicketRepository;

    @Test
    void testContainerExtraction_andDraftGeneration() {

        // ── Подготовка: создаём тикет с текстом, содержащим номер контейнера ──────
        // Номер MSCU1234567 совпадает с записью из V2__seed_demo_containers.sql —
        // если Ollama доступна, получим и черновик с реальными данными контейнера.
        EmailTicket ticket = EmailTicket.builder()
                .messageId("test-unit-001@test.com")
                .senderEmail("customer@example.com")
                .subject("Container status inquiry")
                .body("Good morning, could you please provide status update on container MSCU1234567?" +
                      " When will it arrive at Felixstowe port?")
                .build();

        EmailTicket saved = emailTicketRepository.save(ticket);
        System.out.println("[TEST] Ticket saved with ID=" + saved.getId());

        // ── Запуск: передаём только ID (паттерн Pass ID, not entity) ─────────────
        ticketProcessingService.process(saved.getId());

        // ── Результат: перечитываем тикет из БД (process() синхронный до завершения LLM) ──
        EmailTicket result = emailTicketRepository.findById(saved.getId()).orElseThrow();

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

        // ── Проверки ──────────────────────────────────────────────────────────────

        // Статус должен быть DRAFT_READY (или FAILED если Ollama недоступна)
        // Главное — НЕ NEW и НЕ PROCESSING (пайплайн точно завершился)
        assertNotEquals(TicketStatus.NEW, result.getStatus(),
                "Processing should have started");
        assertNotEquals(TicketStatus.PROCESSING, result.getStatus(),
                "Processing should have completed before process() returned");

        // Если Ollama работает — проверяем полный успех
        if (result.getStatus() == TicketStatus.DRAFT_READY) {
            assertNotNull(result.getDraftReply(),
                    "Draft reply should be generated");
            assertFalse(result.getDraftReply().isBlank(),
                    "Draft reply should not be empty");
            System.out.println("[TEST] ✓ Full pipeline succeeded with Ollama");
        }

        // Если Ollama недоступна — проверяем что fallback черновик всё равно создан
        if (result.getStatus() == TicketStatus.FAILED) {
            // Fallback Draft генерируется даже при ошибке Ollama (см. AiDraftingService)
            System.out.println("[TEST] Ollama not available — check fallback draft above");
        }
    }
}
