package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Vienetų testai pakartotinio apdorojimo planuotojui (RetryScheduler).
 *
 * Tikrina:
 * – biletų su RETRY_LATER būsena ir retryCount < MAX_RETRIES apdorojimą
 * – retryCount ir būsenos atnaujinimą prieš process() kvietimą
 * – Micrometer skaitiklio inkrementavimą kiekvienam biletui
 * – teisingą elgesį kai nėra biletų pertvarkyti
 *
 * Naudoja SimpleMeterRegistry – realus, bet atmintyje veikiantis registras,
 * tinkamas testams (nereikia stubinti sudėtingų Counter mock'ų).
 */
@ExtendWith(MockitoExtension.class)
class RetrySchedulerTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private TicketProcessingService ticketProcessingService;

    private RetryScheduler retryScheduler;
    private SimpleMeterRegistry meterRegistry;

    /** MAX_RETRIES reikšmė iš RetryScheduler klasės (5 bandymai). */
    private static final int MAX_RETRIES = 5;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        retryScheduler = new RetryScheduler(emailTicketRepository, ticketProcessingService, meterRegistry);
    }

    /**
     * Sukuria testo biletą su nurodytu ID ir retryCount.
     */
    private EmailTicket buildRetryTicket(Long id, int retryCount) {
        EmailTicket ticket = new EmailTicket();
        ticket.setId(id);
        ticket.setMessageId("msg-" + id + "@test.com");
        ticket.setStatus(TicketStatus.RETRY_LATER);
        ticket.setRetryCount(retryCount);
        return ticket;
    }

    /**
     * Testas: vienas bilietas su RETRY_LATER – pertvarkomas sėkmingai.
     *
     * Tikrinama:
     * – retryCount padidėja 1
     * – būsena keičiama į NEW
     * – bilietas išsaugomas
     * – process() iškviečiamas su teisingai ID
     */
    @Test
    void retryFailedTickets_oneEligibleTicket_processesAndUpdatesFields() {
        EmailTicket ticket = buildRetryTicket(1L, 2);
        when(emailTicketRepository.findByStatusAndRetryCountLessThan(
                TicketStatus.RETRY_LATER, MAX_RETRIES))
                .thenReturn(List.of(ticket));

        retryScheduler.retryFailedTickets();

        ArgumentCaptor<EmailTicket> captor = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(captor.capture());

        EmailTicket saved = captor.getValue();
        assertThat(saved.getRetryCount()).isEqualTo(3);
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.NEW);

        verify(ticketProcessingService).process(1L);
    }

    /**
     * Testas: keli biletai – visi pertvarkomi, kiekvienam inkrementuojamas skaitiklis.
     *
     * Tikrinama, kad process() iškviečiamas kiekvienam biletui atskirai,
     * o Micrometer skaitiklis atitinka biletų skaičių.
     */
    @Test
    void retryFailedTickets_multipleTickets_processesAllAndIncrementsCounter() {
        EmailTicket t1 = buildRetryTicket(1L, 0);
        EmailTicket t2 = buildRetryTicket(2L, 1);
        EmailTicket t3 = buildRetryTicket(3L, 3);

        when(emailTicketRepository.findByStatusAndRetryCountLessThan(
                TicketStatus.RETRY_LATER, MAX_RETRIES))
                .thenReturn(List.of(t1, t2, t3));

        retryScheduler.retryFailedTickets();

        verify(ticketProcessingService).process(1L);
        verify(ticketProcessingService).process(2L);
        verify(ticketProcessingService).process(3L);

        double counterValue = meterRegistry.counter("smartfreight_tickets_retry_count_total").count();
        assertThat(counterValue).isEqualTo(3.0);
    }

    /**
     * Testas: nėra biletų pertvarkyti – joks apdorojimas nevykdomas.
     *
     * Jei eilė tuščia, planuotojas turi tyliai išeiti – be logging'o,
     * be process() kvietimų, be eilės biletų modifikavimo.
     */
    @Test
    void retryFailedTickets_noTickets_doesNothing() {
        when(emailTicketRepository.findByStatusAndRetryCountLessThan(
                TicketStatus.RETRY_LATER, MAX_RETRIES))
                .thenReturn(List.of());

        retryScheduler.retryFailedTickets();

        verifyNoInteractions(ticketProcessingService);
        verify(emailTicketRepository, never()).save(any());
    }

    /**
     * Testas: saugyklos užklausa naudoja teisingas MAX_RETRIES reikšmę.
     *
     * findByStatusAndRetryCountLessThan() turi būti kviečiamas tiksliai
     * su TicketStatus.RETRY_LATER ir MAX_RETRIES = 5.
     */
    @Test
    void retryFailedTickets_queriesRepositoryWithCorrectParameters() {
        when(emailTicketRepository.findByStatusAndRetryCountLessThan(
                eq(TicketStatus.RETRY_LATER), eq(MAX_RETRIES)))
                .thenReturn(List.of());

        retryScheduler.retryFailedTickets();

        verify(emailTicketRepository).findByStatusAndRetryCountLessThan(
                TicketStatus.RETRY_LATER, MAX_RETRIES);
    }

    /**
     * Testas: eilės tvarka – retryCount didinamas PRIEŠ process() kvietimą.
     *
     * Svarbu: retryCount turi būti išsaugotas PRIEŠ AI apdorojimą.
     * Jei process() nutrūktų, kitą kartą retryCount jau būtų padidintas –
     * apsauga nuo begalinių ciklų.
     */
    @Test
    void retryFailedTickets_incrementsRetryCountBeforeCallingProcess() {
        EmailTicket ticket = buildRetryTicket(5L, 1);
        when(emailTicketRepository.findByStatusAndRetryCountLessThan(
                TicketStatus.RETRY_LATER, MAX_RETRIES))
                .thenReturn(List.of(ticket));

        var inOrder = inOrder(emailTicketRepository, ticketProcessingService);

        retryScheduler.retryFailedTickets();

        inOrder.verify(emailTicketRepository).save(any(EmailTicket.class));
        inOrder.verify(ticketProcessingService).process(5L);
    }
}
