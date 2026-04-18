package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

// REST API для тикетов — используется внешними клиентами (Postman, v2 фронтенд и т.п.).
// Thymeleaf UI работает через TicketViewController; этот контроллер не пересекается с ним.
// Все эндпоинты требуют JWT (SecurityConfig.anyRequest().authenticated()).
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private static final Logger log = LoggerFactory.getLogger(TicketController.class);

    private final EmailTicketRepository emailTicketRepository;

    public TicketController(EmailTicketRepository emailTicketRepository) {
        this.emailTicketRepository = emailTicketRepository;
    }

    // GET /api/tickets[?status=DRAFT_READY]
    // Возвращает список тикетов; опционально фильтр по статусу.
    // Сортировка: свежие сверху (по createdAt DESC).
    @GetMapping
    public List<EmailTicket> list(@RequestParam(name = "status", required = false) String status) {
        if (status == null || status.isBlank()) {
            return emailTicketRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        }
        TicketStatus parsed = parseStatus(status);
        return emailTicketRepository.findByStatus(parsed);
    }

    // GET /api/tickets/{id} — один тикет с черновиком ответа.
    @GetMapping("/{id}")
    public EmailTicket get(@PathVariable Long id) {
        return emailTicketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ticket not found: " + id));
    }

    // POST /api/tickets/{id}/approve — оператор одобрил черновик → CLOSED.
    @Transactional
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, String>> approve(@PathVariable Long id) {
        EmailTicket ticket = emailTicketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ticket not found: " + id));
        ticket.setStatus(TicketStatus.CLOSED);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        log.info("Ticket {} approved by operator → CLOSED", id);
        return ResponseEntity.ok(Map.of("status", TicketStatus.CLOSED.name()));
    }

    // PATCH /api/tickets/{id}/draft — оператор отредактировал черновик ответа.
    // Body: { "draftReply": "...", "close": true|false (опционально) }
    // Если "close": true — статус сразу переводится в CLOSED.
    // Иначе тикет остаётся редактируемым (статус DRAFT_READY).
    // Уже закрытый тикет править нельзя — 409 Conflict.
    @Transactional
    @PatchMapping("/{id}/draft")
    public ResponseEntity<EmailTicket> updateDraft(@PathVariable Long id,
                                                   @RequestBody DraftUpdateRequest request) {
        if (request == null || request.draftReply() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Field 'draftReply' is required");
        }
        EmailTicket ticket = emailTicketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ticket not found: " + id));

        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new ResponseStatusException(CONFLICT, "Ticket " + id + " is closed and cannot be edited");
        }

        ticket.setDraftReply(request.draftReply());
        if (Boolean.TRUE.equals(request.close())) {
            ticket.setStatus(TicketStatus.CLOSED);
        } else if (ticket.getStatus() != TicketStatus.DRAFT_READY) {
            ticket.setStatus(TicketStatus.DRAFT_READY);
        }
        ticket.setUpdatedAt(LocalDateTime.now());
        EmailTicket saved = emailTicketRepository.save(ticket);
        log.info("Ticket {} draft updated by operator (close={}) → {}", id, request.close(), saved.getStatus());
        return ResponseEntity.ok(saved);
    }

    // Запрос на изменение черновика. close=true → сохранить и сразу закрыть тикет.
    public record DraftUpdateRequest(String draftReply, Boolean close) {}

    // POST /api/tickets/{id}/reject — оператор отклонил черновик → RETRY_LATER.
    // RetryScheduler затем подберёт тикет для повторной обработки.
    @Transactional
    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, String>> reject(@PathVariable Long id) {
        EmailTicket ticket = emailTicketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ticket not found: " + id));
        ticket.setStatus(TicketStatus.RETRY_LATER);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        log.info("Ticket {} rejected by operator → RETRY_LATER", id);
        return ResponseEntity.ok(Map.of("status", TicketStatus.RETRY_LATER.name()));
    }

    // Парсит значение query-параметра status в enum. На неверное значение возвращает 400.
    private TicketStatus parseStatus(String raw) {
        try {
            return TicketStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Unknown status '" + raw + "'. Allowed: " + java.util.Arrays.toString(TicketStatus.values()));
        }
    }
}
