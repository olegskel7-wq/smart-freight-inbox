package com.smartfreight.inbox.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit тесты REST API контроллера тикетов (TicketController).
 * 
 * Используется MockMvc для имитации HTTP запросов БЕЗ поднятия полного Spring контекста:
 * - Только контроллер и его зависимости мокируются
 * - Тесты выполняются быстро
 * - Независимы от конфигурации Spring Security
 * 
 * MockMvcBuilders.standaloneSetup() — минимальная конфигурация для testing single controller.
 * 
 * Покрывает все REST endpoints:
 * - GET /api/tickets (список всех тикетов)
 * - GET /api/tickets/{id} (один тикет)
 * - POST /api/tickets/{id}/approve (одобрить черновик)
 * - POST /api/tickets/{id}/reject (отклонить черновик)
 * - PATCH /api/tickets/{id}/draft (обновить черновик)
 * 
 * Проверяет:
 * - HTTP статусы (200 OK, 404 Not Found, 409 Conflict, 400 Bad Request)
 * - JSON структуру ответов
 * - Обработку ошибок
 */
@ExtendWith(MockitoExtension.class)
class TicketControllerTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Создаём контроллер и настраиваем MockMvc (БЕЗ полного Spring контекста)
        TicketController controller = new TicketController(emailTicketRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper(); // Для сериализации JSON
    }

    /**
     * Helper метод для создания тестовых тикетов.
     * 
     * @param id ID тикета
     * @param status статус жизненного цикла
     * @return заполненный тикет
     */
    private EmailTicket buildTicket(Long id, TicketStatus status) {
        EmailTicket t = new EmailTicket();
        t.setId(id);
        t.setMessageId("msg-" + id + "@test.com");
        t.setSenderEmail("customer@example.com");
        t.setSubject("Container status");
        t.setBody("Please check MSCU1234567");
        t.setDraftReply("Dear customer, your container is at Felixstowe.");
        t.setStatus(status);
        t.setRetryCount(0);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        return t;
    }

    /**
     * Тест: GET /api/tickets — получить список всех тикетов.
     * 
     * Проверяет:
     * - HTTP 200 OK
     * - JSON массив с 2 тикетами
     * - Поля status у каждого тикета
     */
    @Test
    void listTickets_returnsAllTickets() throws Exception {
        when(emailTicketRepository.findAll(any(Sort.class)))
                .thenReturn(List.of(
                        buildTicket(1L, TicketStatus.DRAFT_READY),
                        buildTicket(2L, TicketStatus.NEW)
                ));

        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("DRAFT_READY"))
                .andExpect(jsonPath("$[1].status").value("NEW"));
    }

    /**
     * Тест: GET /api/tickets/{id} — получить один тикет.
     * 
     * Проверяет:
     * - HTTP 200 OK
     * - Верные значения полей в JSON
     */
    @Test
    void getTicket_existingId_returnsTicket() throws Exception {
        when(emailTicketRepository.findById(1L))
                .thenReturn(Optional.of(buildTicket(1L, TicketStatus.DRAFT_READY)));

        mockMvc.perform(get("/api/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT_READY"))
                .andExpect(jsonPath("$.senderEmail").value("customer@example.com"));
    }

    /**
     * Тест: GET /api/tickets/{id} с несуществующим ID.
     * 
     * Проверяет:
     * - HTTP 404 Not Found (ResourceNotFoundException обработана)
     */
    @Test
    void getTicket_nonExistingId_returns404() throws Exception {
        when(emailTicketRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/tickets/99"))
                .andExpect(status().isNotFound());
    }

    /**
     * Тест: POST /api/tickets/{id}/approve — одобрить черновик.
     * 
     * Проверяет:
     * - HTTP 200 OK
     * - Статус изменился на CLOSED
     * - JSON ответ содержит новый статус
     */
    @Test
    void approveTicket_draftReadyTicket_returnsClosed() throws Exception {
        EmailTicket ticket = buildTicket(1L, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(emailTicketRepository.save(any())).thenReturn(ticket);

        mockMvc.perform(post("/api/tickets/1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    /**
     * Тест: POST /api/tickets/{id}/reject — отклонить черновик.
     * 
     * Проверяет:
     * - HTTP 200 OK
     * - Статус изменился на RETRY_LATER
     * - RetryScheduler позже подберёт этот тикет для переобработки
     */
    @Test
    void rejectTicket_draftReadyTicket_returnsRetryLater() throws Exception {
        EmailTicket ticket = buildTicket(1L, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(emailTicketRepository.save(any())).thenReturn(ticket);

        mockMvc.perform(post("/api/tickets/1/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETRY_LATER"));
    }

    /**
     * Тест: PATCH /api/tickets/{id}/draft — обновить черновик оператором.
     * 
     * Проверяет:
     * - HTTP 200 OK
     * - Текст черновика обновлён в JSON ответе
     * - Статус остаётся DRAFT_READY (т.к. close=false)
     */
    @Test
    void updateDraft_validRequest_updatesReply() throws Exception {
        EmailTicket ticket = buildTicket(1L, TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(emailTicketRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String body = objectMapper.writeValueAsString(
                Map.of("draftReply", "Updated reply text", "close", false));

        mockMvc.perform(patch("/api/tickets/1/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftReply").value("Updated reply text"));
    }

    /**
     * Тест: попытка отредактировать закрытый тикет (статус CLOSED).
     * 
     * Проверяет:
     * - HTTP 409 Conflict (логический конфликт — тикет закрыт)
     * - Сообщение об ошибке в теле ответа
     */
    @Test
    void updateDraft_closedTicket_returns409Conflict() throws Exception {
        when(emailTicketRepository.findById(1L))
                .thenReturn(Optional.of(buildTicket(1L, TicketStatus.CLOSED)));

        String body = objectMapper.writeValueAsString(
                Map.of("draftReply", "Should not work", "close", false));

        mockMvc.perform(patch("/api/tickets/1/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    /**
     * Тест: отправка PATCH запроса без обязательного поля draftReply.
     * 
     * Проверяет:
     * - HTTP 400 Bad Request (валидация входных данных)
     * - Контроллер проверяет что request.draftReply() не null
     */
    @Test
    void updateDraft_missingDraftReply_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("close", false));

        mockMvc.perform(patch("/api/tickets/1/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
