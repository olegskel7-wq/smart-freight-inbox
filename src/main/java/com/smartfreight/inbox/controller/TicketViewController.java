package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.EmailReplyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Thymeleaf UI valdiklis operatoriaus naršyklės sąsajai.
 *
 * Autentifikacija per standartinį Spring Security formų prisijungimą (sesija cookie).
 * Vienintelis išorinis valdiklis – REST API pašalintas kaip nereikalingas demo versijai.
 */
@Controller
public class TicketViewController {

    private static final Logger log = LoggerFactory.getLogger(TicketViewController.class);

    private final EmailTicketRepository emailTicketRepository;
    private final EmailReplyService emailReplyService;

    public TicketViewController(EmailTicketRepository emailTicketRepository,
                                EmailReplyService emailReplyService) {
        this.emailTicketRepository = emailTicketRepository;
        this.emailReplyService = emailReplyService;
    }

    /** GET / – nukreipimas į prisijungimo puslapį. */
    @GetMapping("/")
    public String root() {
        return "redirect:/login";
    }

    /** GET /login – prisijungimo formos puslapio rodymas. */
    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    /**
     * Leidžiamų rikiavimo stulpelių baltasis sąrašas.
     *
     * Bet kokia kita sort reikšmė ignoruojama ir pakeičiama į createdAt –
     * apsauga nuo PropertyReferenceException, jei klientas perduoda
     * neegzistuojančio JPA lauko pavadinimą.
     */
    private static final Set<String> SORTABLE_COLUMNS = Set.of(
            "id", "senderEmail", "subject", "extractedContainerNumber",
            "status", "createdAt", "updatedAt"
    );
    private static final String DEFAULT_SORT = "createdAt";

    /**
     * GET /tickets – biletų sąrašas su serverio rikiavimo ir būsenos filtru.
     *
     * Parametrai:
     * – {@code ?sort=<stulpelis>} – rikiavimo stulpelis (baltasis sąrašas viršuje)
     * – {@code ?dir=asc|desc} – kryptis (numatytasis: desc – naujausieji viršuje)
     * – {@code ?status=DRAFT_READY} – pasirenkamas būsenos filtras
     *
     * @param sort   rikiavimo stulpelio pavadinimas
     * @param dir    rikiavimo kryptis
     * @param status pasirenkamas būsenos filtras
     * @param model  Thymeleaf modelio objektas
     * @return šablono "tickets" pavadinimas
     */
    @GetMapping("/tickets")
    public String listTickets(@RequestParam(name = "sort", defaultValue = DEFAULT_SORT) String sort,
                              @RequestParam(name = "dir", defaultValue = "desc") String dir,
                              @RequestParam(name = "status", required = false) String status,
                              Model model) {

        // Filtruojame rikiavimo stulpelį per baltąjį sąrašą
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

    /**
     * Paverčia teksto parametrą į TicketStatus enum, arba grąžina null jei tuščias/netinkamas.
     *
     * UI tolerantiškas klaidoms – neleistina būsena tiesiog ignoruojama,
     * neleistina reikšmė tiesiog ignoruojama (UI tolerantiškas klaidoms).
     */
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

    /**
     * GET /tickets/{id} – bilieto detalių puslapio rodymas.
     *
     * ResponseStatusException(404) leidžia Spring Boot:
     * 1) grąžinti teisingą HTTP 404 statusą (ne 500 "whitelabel");
     * 2) perduoti valdymą BasicErrorController, kuris ateityje atrengs templates/error.html.
     * GlobalExceptionHandler čia NVEIKIA – jis apribotas @RestController klasėmis.
     *
     * @param id    bilieto identifikatorius
     * @param model Thymeleaf modelio objektas
     * @return šablono "ticket-detail" pavadinimas arba 404
     */
    @GetMapping("/tickets/{id}")
    public String ticketDetail(@PathVariable Long id, Model model) {
        EmailTicket ticket = emailTicketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ticket not found: " + id));
        model.addAttribute("ticket", ticket);
        return "ticket-detail";
    }

    /**
     * POST /tickets/{id}/delete – soft-deletes a ticket (sets deleted = true via @SQLDelete).
     *
     * The record remains in the database for audit purposes but is hidden from all queries
     * filtered by @SQLRestriction (deleted = false).
     *
     * @param id                 bilieto identifikatorius
     * @param redirectAttributes flash message on success
     * @return nukreipimas į biletų sąrašą
     */
    @Transactional
    @PostMapping("/tickets/{id}/delete")
    public String deleteTicket(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        emailTicketRepository.findById(id).ifPresent(ticket -> {
            emailTicketRepository.delete(ticket);
            log.info("Ticket {} soft-deleted", id);
        });
        redirectAttributes.addFlashAttribute("successMessage", "Ticket #" + id + " deleted.");
        return "redirect:/tickets";
    }

    /**
     * POST /tickets/{id}/close – operatorius uždaro biletą (atsakymas išsiųstas / klausimas išspręstas).
     *
     * @param id bilieto identifikatorius
     * @return nukreipimas į biletų sąrašą
     */
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

    /**
     * POST /tickets/{id}/draft – operatorius redagavo atsakymo juodraštį.
     *
     * Veiksmo parametras {@code action}:
     * – {@code "save"}  – tik išsaugoti ir grįžti į detales
     * – {@code "close"} – išsaugoti ir uždaryti biletą (be laiško siuntimo)
     * – {@code "send"}  – išsaugoti, išsiųsti laišką siuntėjui, uždaryti biletą
     *
     * Uždaryto bilieto redaguoti negalima – grįžtama į biletų sąrašą be pakeitimų.
     *
     * @param id                 bilieto identifikatorius
     * @param draftReply         redaguotas juodraščio tekstas
     * @param action             norimas veiksmas (save / close / send)
     * @param redirectAttributes Flash pranešimams (sėkmė / klaida)
     * @return nukreipimo URL
     */
    @Transactional
    @PostMapping("/tickets/{id}/draft")
    public String updateDraft(@PathVariable Long id,
                              @RequestParam("draftReply") String draftReply,
                              @RequestParam(name = "action", defaultValue = "save") String action,
                              RedirectAttributes redirectAttributes) {
        EmailTicket ticket = emailTicketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ticket not found: " + id));

        if (ticket.getStatus() == TicketStatus.CLOSED) {
            return "redirect:/tickets/" + id;
        }

        ticket.setDraftReply(draftReply);

        if ("send".equalsIgnoreCase(action)) {
            try {
                emailReplyService.sendReply(ticket);
                ticket.setStatus(TicketStatus.CLOSED);
                log.info("Ticket {} draft sent to {} and closed", id, ticket.getSenderEmail());
                redirectAttributes.addFlashAttribute("successMessage",
                        "Reply sent to " + ticket.getSenderEmail());
            } catch (Exception e) {
                log.error("Bilieto {} siuntimas nepavyko: {}", id, e.getMessage(), e);
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Failed to send email: " + e.getMessage());
                return "redirect:/tickets/" + id;
            }
        } else if ("close".equalsIgnoreCase(action)) {
            ticket.setStatus(TicketStatus.CLOSED);
        } else if (ticket.getStatus() != TicketStatus.DRAFT_READY) {
            // Operatorius pats įvedė atsakymą biletui be sugeneruoto juodraščio –
            // laikoma, kad juodraštis paruoštas (NEW/PROCESSING/RETRY_LATER/FAILED → DRAFT_READY).
            ticket.setStatus(TicketStatus.DRAFT_READY);
        }
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);

        if ("send".equalsIgnoreCase(action) || "close".equalsIgnoreCase(action)) {
            return "redirect:/tickets";
        }
        return "redirect:/tickets/" + id;
    }
}
