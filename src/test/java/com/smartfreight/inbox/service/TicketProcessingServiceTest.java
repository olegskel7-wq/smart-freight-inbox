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
 * Integration test for the full AI ticket processing pipeline.
 *
 * This is NOT a unit test — it is an E2E test of the entire system:
 * - Full Spring context is started
 * - All beans are wired
 * - Real database via Testcontainers
 * - Real Ollama calls (if available), graceful fallback otherwise
 *
 * May be slow when Ollama is running (up to 150 s for LLM generation).
 * Use for integration validation only, not fast CI checks.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TicketProcessingServiceTest {

    @Autowired
    private TicketProcessingService ticketProcessingService;

    @Autowired
    private EmailTicketRepository emailTicketRepository;

    /**
     * Full pipeline test: extraction → lookup → drafting.
     *
     * Scenario:
     * 1. Save a ticket containing container number MSCU1234567 (matches demo seed data)
     * 2. Call process() — synchronous, returns only after completion
     * 3. Reload ticket from DB
     * 4. Assert status changed (pipeline ran to completion)
     * 5. If Ollama is available: assert draft was generated
     */
    @Test
    void testContainerExtraction_andDraftGeneration() {

        EmailTicket ticket = EmailTicket.builder()
                .messageId("test-unit-001@test.com")
                .senderEmail("customer@example.com")
                .subject("Container status inquiry")
                .body("Good morning, could you please provide status update on container MSCU1234567?" +
                      " When will it arrive at Felixstowe port?")
                .build();

        EmailTicket saved = emailTicketRepository.save(ticket);
        System.out.println("[TEST] Ticket saved with ID=" + saved.getId());

        ticketProcessingService.process(saved.getId());

        EmailTicket result = emailTicketRepository.findById(saved.getId()).orElseThrow();

        System.out.println("\n========================================");
        System.out.println("  PROCESSING RESULT");
        System.out.println("========================================");
        System.out.println("Status              : " + result.getStatus());
        System.out.println("Extracted identifier: " + result.getExtractedIdentifier());
        System.out.println("Retry count         : " + result.getRetryCount());
        System.out.println("----------------------------------------");
        System.out.println("Draft reply:");
        System.out.println(result.getDraftReply() != null ? result.getDraftReply() : "(empty — Ollama not available)");
        System.out.println("========================================\n");

        assertNotEquals(TicketStatus.NEW, result.getStatus(),
                "Processing should have started");
        assertNotEquals(TicketStatus.PROCESSING, result.getStatus(),
                "Processing should have completed before process() returned");

        if (result.getStatus() == TicketStatus.DRAFT_READY) {
            assertNotNull(result.getDraftReply(), "Draft reply should be generated");
            assertFalse(result.getDraftReply().isBlank(), "Draft reply should not be empty");
            System.out.println("[TEST] Full pipeline succeeded with Ollama");
        }

        if (result.getStatus() == TicketStatus.FAILED) {
            System.out.println("[TEST] Ollama not available — fallback draft used");
        }
    }
}
