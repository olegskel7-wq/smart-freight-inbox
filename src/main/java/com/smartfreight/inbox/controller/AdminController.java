package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administravimo API valdiklis techninės priežiūros operacijoms (tik localhost).
 *
 * Saugo "pavojingas" operacijas kūrėjams ir administratoriams:
 * – Nepavykusių / įstrigusių biletų persvarstymas
 * – (ateityje) duomenų valymas, rankinis konvejerio paleidimas iš naujo ir pan.
 *
 * Apsauga: visos galūnės tikrina, kad užklausa ateina iš localhost (127.0.0.1 arba ::1).
 * Prielaida: produkcijoje šis valdiklis nebus pasiekiamas iš išorės
 * (ugniasienė / atvirkštinis proxy neperduoda prievado į išorę).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final EmailTicketRepository emailTicketRepository;
    private final TicketProcessingService ticketProcessingService;

    public AdminController(EmailTicketRepository emailTicketRepository,
                           TicketProcessingService ticketProcessingService) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketProcessingService = ticketProcessingService;
    }

    /**
     * POST /admin/reprocess – persvarstyti visus įstrigusius / nepavykusius bilietus.
     *
     * Naudojama kai:
     * 1. Ollama buvo nepasiekiamas ir daug biletų perėjo į FAILED.
     * 2. Po Ollama atstatymo reikia juos persvarstyti.
     * 3. Operatorius atmetė juodraštį (RETRY_LATER) ir reikia paleisti AI iš naujo.
     *
     * Algoritmas:
     * 1. Patikriname, kad užklausa ateina iš localhost.
     * 2. Masinis FAILED → NEW atstatymas atskiroje trumpoje transakcijoje.
     * 3. Gauname TIK biletų ID su NEW ir RETRY_LATER būsenomis (projekcija).
     * 4. Sinchroniškai vykdome process(id) kiekvienam biletui esamame sraute.
     *    Kiekvienas process() veikia savo transakcijoje (REQUIRES_NEW),
     *    todėl HikariCP ryšiai atlaisvinami po kiekvieno bilieto.
     * 5. Grąžiname persvarstytos biletų skaičių.
     *
     * Architektūros pastaba: metodas NETURI @Transactional,
     * nes viena "stora" transakcija visam ciklui užblokuotų HikariCP ryšį
     * visam Ollama laukimo laikui (iki 150 s × biletų skaičius).
     *
     * @param request HTTP užklausa (reikalinga remoteAddr tikrinimui)
     * @return 200 OK su persvarstytos biletų skaičiumi, arba 403 jei ne localhost
     */
    @PostMapping("/reprocess")
    public ResponseEntity<String> reprocessAll(HttpServletRequest request) {
        if (!isLocalhost(request)) {
            log.warn("Rejected /admin/reprocess from remote address: {}", request.getRemoteAddr());
            return ResponseEntity.status(403).body("Forbidden: admin endpoints are local-only.");
        }

        // FAILED → NEW masinis atstatymas atskiroje trumpoje transakcijoje.
        // Po grąžinimo DB ryšys iš karto grąžinamas į HikariCP baseiną.
        int reset = ticketProcessingService.prepareForRetry();

        // Gauname tik ID – nekeliame sunkių TEXT laukų į atmintį.
        // process(id) pats perskaitys biletą savo transakcijoje (pattern "perduok ID, ne esybę").
        List<Long> ids = emailTicketRepository.findIdsByStatusIn(
                List.of(TicketStatus.NEW, TicketStatus.RETRY_LATER));

        if (ids.isEmpty()) {
            return ResponseEntity.ok("No tickets to reprocess (reset " + reset + " from FAILED).");
        }

        int count = ids.size();
        log.info("Synchronous reprocessing started for {} ticket(s) (reset {} from FAILED)", count, reset);
        ids.forEach(ticketProcessingService::process);
        log.info("Synchronous reprocessing finished for {} ticket(s)", count);

        return ResponseEntity.ok("Reprocessed " + count + " tickets (reset " + reset + " from FAILED).");
    }

    /**
     * Patikrina, ar užklausa atėjo iš lokalaus kompiuterio.
     *
     * Palaikomi formatai:
     * – {@code 127.0.0.1} – IPv4 loopback
     * – {@code ::1} – IPv6 loopback (trumpa forma)
     * – {@code 0:0:0:0:0:0:0:1} – IPv6 loopback (pilna forma)
     *
     * Pastaba: jei programa veikia už atvirkštinio proxy (nginx/traefik),
     * reikia konfigūruoti {@code server.forward-headers-strategy=NATIVE}
     * arba riboti prieigą prie /admin/** proxy lygmenyje.
     *
     * @param request HTTP užklausa
     * @return true jei remoteAddr rodo į localhost, kitaip false
     */
    private boolean isLocalhost(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return "127.0.0.1".equals(addr) || "::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr);
    }
}
