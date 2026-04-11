package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
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

// Thymeleaf UI контроллер — обслуживает браузерный интерфейс оператора.
// Использует JWT cookie для аутентификации (устанавливается при POST /login).
// REST API (/api/**) остаётся нетронутым для программного доступа.
@Controller
public class TicketViewController {

    private final AuthService authService;
    private final EmailTicketRepository emailTicketRepository;

    public TicketViewController(AuthService authService,
                                EmailTicketRepository emailTicketRepository) {
        this.authService = authService;
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

    // POST /login — проверить учётные данные, установить JWT cookie, перейти к тикетам
    @PostMapping("/login")
    public String doLogin(@RequestParam String email,
                          @RequestParam String password,
                          HttpServletResponse response,
                          Model model) {
        try {
            String token = authService.login(email, password);
            Cookie jwtCookie = new Cookie("jwt", token);
            jwtCookie.setHttpOnly(true);
            jwtCookie.setPath("/");
            jwtCookie.setMaxAge(86400); // 24 часа
            response.addCookie(jwtCookie);
            return "redirect:/tickets";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", "Неверный email или пароль");
            return "login";
        }
    }

    // POST /logout — удалить JWT cookie, перейти на логин
    @PostMapping("/logout")
    public String doLogout(HttpServletResponse response) {
        Cookie jwtCookie = new Cookie("jwt", "");
        jwtCookie.setHttpOnly(true);
        jwtCookie.setPath("/");
        jwtCookie.setMaxAge(0);
        response.addCookie(jwtCookie);
        return "redirect:/login";
    }

    // GET /tickets — список всех тикетов, отсортированных по дате (новые сверху)
    @GetMapping("/tickets")
    public String listTickets(Model model) {
        List<EmailTicket> tickets = emailTicketRepository
                .findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        model.addAttribute("tickets", tickets);
        return "tickets";
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
}
