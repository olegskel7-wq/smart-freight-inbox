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

/**
 * AI konvejerio orkestratorius biletų apdorojimui.
 *
 * Koordinuoja tris žingsnius:
 * 1. Konteinerio numerio ištraukimas (Qwen 2.5) iš laiško teksto
 * 2. Konteinerio paieška port-service sistemoje
 * 3. Atsakymo juodraščio generavimas (Llama 3.1)
 *
 * Vykdomas sinchroniškai kviečiančiojo sraute
 * ({@link com.smartfreight.inbox.integration.EmailIngestionService} / {@link com.smartfreight.inbox.scheduler.RetryScheduler}).
 *
 * Apsauga nuo optimistinės užraktos konfliktų: automatiškai kartoja iki MAX_RETRY_ATTEMPTS
 * (3 kartų) kai Hibernate aptinka versijos nesutapimą.
 */
@Service
public class TicketProcessingService {

    private static final Logger log = LoggerFactory.getLogger(TicketProcessingService.class);

    /** Maksimalus bandymų skaičius esant optimistinės užraktos konfliktui. */
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
                .description("Bilieto apdorojimo trukmė AI konvejeriu")
                .register(meterRegistry);
        this.draftReadyCounter = Counter.builder("smartfreight_tickets_status_total")
                .tag("status", "DRAFT_READY")
                .description("Biletų skaičius pagal būseną")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("smartfreight_tickets_status_total")
                .tag("status", "FAILED")
                .description("Biletų skaičius pagal būseną")
                .register(meterRegistry);
    }

    /**
     * Apdoroja biletą per trijų žingsnių AI konvejerį.
     *
     * Propagation.REQUIRES_NEW – kuria naują transakciją kiekvienam kvietimui.
     * Būtina, nes metodas kviečiamas iš afterCommit() callback'o,
     * kai dabartinė transakcija jau uždaryta.
     *
     * Kartojimo logika: jei Hibernate aptinka versijos konfliktą
     * ({@link ObjectOptimisticLockingFailureException}), metodas kartoja iki
     * MAX_RETRY_ATTEMPTS (3) kartų prieš pažymint biletą FAILED.
     *
     * Patten "perduok ID, ne esybę": bilietas persikraunamas šios transakcijos viduje,
     * nes perduotas objektas būtų detached būsenoje ir sukeltų LazyInitializationException.
     *
     * @param ticketId apdorojamo bilieto identifikatorius
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long ticketId) {
        MDC.put("ticketId", String.valueOf(ticketId));
        long startTime = System.nanoTime();
        try {
            log.info("Starting AI processing");

            for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
                try {
                    EmailTicket ticket = emailTicketRepository.findById(ticketId)
                            .orElseThrow(() -> new IllegalArgumentException("Bilietas nerastas: " + ticketId));

                    // Apsauga nuo dvigubo apdorojimo – priimame tik NEW ir RETRY_LATER
                    if (ticket.getStatus() != TicketStatus.NEW && ticket.getStatus() != TicketStatus.RETRY_LATER) {
                        log.warn("Ticket already in state {}, skipping", ticket.getStatus());
                        return;
                    }

                    // Užfiksuojame biletą – perkeliame į PROCESSING.
                    // saveAndFlush iš karto matomas kitiems srautams (nelaukia transakcijos pabaigos).
                    ticket.setStatus(TicketStatus.PROCESSING);
                    ticket.setUpdatedAt(LocalDateTime.now());
                    emailTicketRepository.saveAndFlush(ticket);

                    // ── 1 žingsnis: Konteinerio numerio ištraukimas ──────────────────────
                    // Qwen 2.5 analizuoja laiško turinį ir randa numerį pagal ISO 6346 formatą.
                    // Jei Ollama nepasiekiamas – naudojamas regex atsarginis variantas.
                    log.info("Step 1/3: Extracting container number via Qwen 2.5");
                    String containerNumber = aiExtractionService.extractContainerNumber(ticket.getBody());
                    ticket.setExtractedContainerNumber(containerNumber.isEmpty() ? null : containerNumber);
                    ticket.setUpdatedAt(LocalDateTime.now());
                    emailTicketRepository.save(ticket);

                    // ── 2 žingsnis: Konteinerio paieška ─────────────────────────────────
                    // Ieškome konteinerio pagal ištrauktą numerį. Nerastas → perduodame null.
                    log.info("Step 2/3: Looking up container '{}' in database", containerNumber);
                    Optional<Container> containerOpt = containerNumber.isEmpty()
                            ? Optional.empty()
                            : containerLookupService.findByNumber(containerNumber);

                    Container container = containerOpt.orElse(null);
                    if (container == null) {
                        log.warn("Container '{}' not found — draft will reflect missing data", containerNumber);
                    }

                    // ── 3 žingsnis: Juodraščio generavimas ──────────────────────────────
                    // Llama 3.1 kuria profesionalų atsakymą pagal laišką ir konteinerio duomenis.
                    // Jei Ollama nepasiekiamas – grąžinamas statinis atsarginis juodraštis.
                    log.info("Step 3/3: Generating reply draft via Llama 3.1");
                    String draft = aiDraftingService.generateDraft(ticket.getBody(), container);
                    ticket.setDraftReply(draft);

                    // ── Pabaiga: išsaugomas rezultatas ──────────────────────────────────
                    ticket.setStatus(TicketStatus.DRAFT_READY);
                    ticket.setUpdatedAt(LocalDateTime.now());
                    emailTicketRepository.save(ticket);

                    draftReadyCounter.increment();
                    processingTimer.record(System.nanoTime() - startTime, java.util.concurrent.TimeUnit.NANOSECONDS);
                    log.info("Bilieto apdorojimas baigtas: DRAFT_READY");
                    return;

                } catch (ObjectOptimisticLockingFailureException e) {
                    // Versijos konfliktas: kitas srautas spėjo pakeisti įrašą.
                    log.warn("Optimistic lock conflict on attempt {}/{}, retrying", attempt + 1, MAX_RETRY_ATTEMPTS);
                    if (attempt == MAX_RETRY_ATTEMPTS - 1) {
                        log.error("All retry attempts exhausted — marking ticket as FAILED");
                        markFailed(ticketId);
                    }
                } catch (Exception e) {
                    log.error("Unexpected error during ticket processing", e);
                    markFailed(ticketId);
                    return;
                }
            }
        } finally {
            MDC.remove("ticketId");
        }
    }

    /**
     * Pažymi biletą kaip FAILED – pakartotinis apdorojimas neįmanomas be rankinio įsikišimo.
     *
     * @param ticketId bilieto identifikatorius
     */
    private void markFailed(Long ticketId) {
        emailTicketRepository.findById(ticketId).ifPresent(t -> {
            t.setStatus(TicketStatus.FAILED);
            t.setUpdatedAt(LocalDateTime.now());
            emailTicketRepository.save(t);
            failedCounter.increment();
        });
    }

    /**
     * Paruošia bilietus masiniams persvarstymas – masinis FAILED → NEW atstatymas.
     *
     * Naudojamas {@link com.smartfreight.inbox.controller.AdminController#reprocessAll}.
     * Išskirtas į atskirą @Transactional metodą dėl dviejų priežasčių:
     * 1. Transakcija trumpa (vienas UPDATE, momentinis commit).
     *    HikariCP ryšys iš karto grąžinamas į baseiną.
     * 2. Masinis atnaujinimas reikalauja @Transactional + @Modifying JPA lygmenyje,
     *    kitaip Hibernate meta TransactionRequiredException.
     *
     * @return perkelstų iš FAILED į NEW biletų skaičius
     */
    @Transactional
    public int prepareForRetry() {
        int reset = emailTicketRepository.resetFailedToNew();
        if (reset > 0) {
            log.info("prepareForRetry: reset {} FAILED ticket(s) to NEW", reset);
        }
        return reset;
    }
}
