package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

// Thymeleaf UI контроллер — обслуживает браузерный интерфейс оператора.
// Аутентификация через стандартный Spring Security form login (сессия в cookie).
@Controller
public class TicketViewController {

    private final EmailTicketRepository emailTicketRepository;

    public TicketViewController(EmailTicketRepository emailTicketRepository) {
        this.emailTicketRepository = emailTicketRepository;
    }

    // GET / — редирект на страницу логина
    @GetMapping("/")
    public String root() {
        return "redirect:/login";
    }

    // GET /login — показать форму входа
    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    // Колонки, по которым разрешена сортировка (whitelist).
    // Любое другое значение sort игнорируется и заменяется на createdAt — защита от
    // случайного передачи имени поля, которого нет в JPA-сущности (PropertyReferenceException).
    private static final Set<String> SORTABLE_COLUMNS = Set.of(
            "id", "senderEmail", "subject", "extractedContainerNumber",
            "status", "createdAt", "updatedAt"
    );
    private static final String DEFAULT_SORT = "createdAt";

    // GET /tickets — список тикетов с серверной сортировкой и фильтром по статусу.
    //   ?sort=<column>     — какая колонка сортируется (whitelist выше)
    //   ?dir=asc|desc      — направление (по умолчанию desc — свежие сверху)
    //   ?status=DRAFT_READY — опциональный фильтр по статусу
    @GetMapping("/tickets")
    public String listTickets(@RequestParam(name = "sort", defaultValue = DEFAULT_SORT) String sort,
                              @RequestParam(name = "dir", defaultValue = "desc") String dir,
                              @RequestParam(name = "status", required = false) String status,
                              Model model) {

        String safeSort = SORTABLE_COLUMNS.contains(sort) ? sort : DEFAULT_SORT;
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sortSpec = Sort.by(direction, safeSort);

        List<EmailTicket> tickets;
        TicketStatus statusFilter = parseStatusOrNull(status);
        if (statusFilter != null) {
            tickets = emailTicketRepository.findByStatus(statusFilter, sortSpec);
        } else {
            tickets = emailTicketRepository.findAll(sortSpec);
        }

        model.addAttribute("tickets", tickets);
        model.addAttribute("sort", safeSort);
        model.addAttribute("dir", direction.name().toLowerCase());
        model.addAttribute("statusFilter", statusFilter == null ? "" : statusFilter.name());
        model.addAttribute("allStatuses", TicketStatus.values());
        return "tickets";
    }

    private TicketStatus parseStatusOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TicketStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // GET /tickets/{id} — детали тикета: письмо, контейнер, черновик ответа
    @GetMapping("/tickets/{id}")
    public String ticketDetail(@PathVariable Long id, Model model) {
        EmailTicket ticket = emailTicketRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + id));
        model.addAttribute("ticket", ticket);
        return "ticket-detail";
    }

    // POST /tickets/{id}/close — оператор закрывает тикет (ответ отправлен / вопрос решён)
    @Transactional
    @PostMapping("/tickets/{id}/close")
    public String closeTicket(@PathVariable Long id) {
        emailTicketRepository.findById(id).ifPresent(ticket -> {
            ticket.setStatus(TicketStatus.CLOSED);
            ticket.setUpdatedAt(LocalDateTime.now());
            emailTicketRepository.save(ticket);
        });
        return "redirect:/tickets";
    }

    // POST /tickets/{id}/draft — оператор отредактировал черновик ответа.
    // Параметр action: "save"  — только сохранить и вернуться к деталям;
    //                  "close" — сохранить отредактированный текст и сразу закрыть тикет.
    // Закрытые тикеты редактировать нельзя — возврат на список без изменений.
    @Transactional
    @PostMapping("/tickets/{id}/draft")
    public String updateDraft(@PathVariable Long id,
                              @RequestParam("draftReply") String draftReply,
                              @RequestParam(name = "action", defaultValue = "save") String action) {
        EmailTicket ticket = emailTicketRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + id));

        if (ticket.getStatus() == TicketStatus.CLOSED) {
            return "redirect:/tickets/" + id;
        }

        ticket.setDraftReply(draftReply);
        if ("close".equalsIgnoreCase(action)) {
            ticket.setStatus(TicketStatus.CLOSED);
        } else if (ticket.getStatus() != TicketStatus.DRAFT_READY) {
            // Если оператор сам впечатал ответ для тикета без сгенерированного черновика —
            // считаем, что черновик готов (статус NEW/PROCESSING/RETRY_LATER/FAILED → DRAFT_READY).
            ticket.setStatus(TicketStatus.DRAFT_READY);
        }
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);

        return "close".equalsIgnoreCase(action)
                ? "redirect:/tickets"
                : "redirect:/tickets/" + id;
    }
}
