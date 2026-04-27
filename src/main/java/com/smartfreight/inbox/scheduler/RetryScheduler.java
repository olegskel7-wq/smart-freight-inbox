package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Įstrigusių biletų pakartotinio apdorojimo planuotojas.
 *
 * Periodiškai (kas 2 minutes) ieško biletų su RETRY_LATER būsena
 * ir automatiškai paleidžia AI konvejerį iš naujo.
 *
 * Naudojimo atvejai:
 * – Automatinis pakartojimas esant laikinoms Ollama klaidoms
 * – Operatoriaus atmestų juodraščių persvarstymas (RETRY_LATER būsena)
 *
 * Konvejeris:
 * 1. Ieškome RETRY_LATER biletų su retryCount &lt; MAX_RETRIES (5)
 * 2. Kiekvienam: padidinamas retryCount, būsena atstatoma į NEW
 * 3. Paleidžiamas TicketProcessingService.process() sinchroniškai
 * 4. Sėkmė → DRAFT_READY, klaida → vėl RETRY_LATER arba FAILED
 *
 * MAX_RETRIES apsaugo nuo begalinių ciklų su "sugedusiais" biletais.
 */
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    /**
     * Maksimalus apdorojimo bandymų skaičius vienam biletui.
     * Po to bilietas lieka RETRY_LATER – operatorius turi spręsti rankiniu būdu.
     */
    private static final int MAX_RETRIES = 5;

    private final EmailTicketRepository emailTicketRepository;
    private final TicketProcessingService ticketProcessingService;
    private final Counter retryCounter;

    public RetryScheduler(EmailTicketRepository emailTicketRepository,
                          TicketProcessingService ticketProcessingService,
                          MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketProcessingService = ticketProcessingService;
        this.retryCounter = Counter.builder("smartfreight_tickets_retry_count_total")
                .description("Total number of retry processing attempts for tickets")
                .register(meterRegistry);
    }

    /**
     * Periodiškai vykdoma užduotis įstrigusiems biletams persvarstyti.
     *
     * Grafikas:
     * – {@code fixedDelay = 120_000 ms} (2 minutės) – pauzė TARP vieno vykdymo pabaigos ir kito pradžios
     * – {@code initialDelay = 60_000 ms} (1 minutė) – pauzė prieš PIRMĄ paleidimą
     *   (duoda laikui programai inicializuotis po paleidimo)
     *
     * Naudojamas fixedDelay vietoj fixedRate, kad nekauptųsi lygiagrečiai vykdymai:
     * – fixedRate: paleidžia kas 2 minutes, nepriklausomai nuo metodo trukmės
     * – fixedDelay: jei metodas vykdėsi 5 minutes, kitas paleidimas bus po 2 minučių nuo pabaigos
     *
     * Ollama gali laukti 150 s vienam biletui, todėl fixedDelay tinkamesnis.
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    public void retryFailedTickets() {
        List<EmailTicket> toRetry = emailTicketRepository
                .findByStatusAndRetryCountLessThan(TicketStatus.RETRY_LATER, MAX_RETRIES);

        if (toRetry.isEmpty()) {
            // Nėra darbo – tyliai išeiname (nereikia loginti kiekvieną tuščią ciklą)
            return;
        }

        log.info("RetryScheduler: found {} ticket(s) to retry", toRetry.size());

        for (EmailTicket ticket : toRetry) {
            // retryCount didinamas PRIEŠ paleidimą – jei process() lūžtų,
            // kitą kartą skaitiklis jau bus padidintas (apsauga nuo begalinių ciklų)
            ticket.setRetryCount(ticket.getRetryCount() + 1);
            // Atstatome į NEW – TicketProcessingService.process() priima tik NEW arba RETRY_LATER
            ticket.setStatus(TicketStatus.NEW);
            emailTicketRepository.save(ticket);

            log.info("Retrying ticket ID={} (attempt {} of {})",
                    ticket.getId(), ticket.getRetryCount(), MAX_RETRIES);

            retryCounter.increment();
            // Sinchroninis paleidimas esamame sraute – @Transactional process() sukuria naują transakciją
            ticketProcessingService.process(ticket.getId());
        }
    }
}
