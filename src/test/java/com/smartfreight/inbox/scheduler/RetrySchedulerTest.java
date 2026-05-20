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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.TaskExecutor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RetryScheduler.
 *
 * Verifies:
 * - RETRY_LATER tickets with retryCount < MAX_RETRIES are processed
 * - retryCount and status are updated before process() is called
 * - Micrometer counter is incremented per ticket
 * - Correct behaviour when no tickets are eligible
 *
 * Uses SimpleMeterRegistry — a real in-memory registry suitable for tests.
 * TaskExecutor is mocked with doAnswer so async calls run synchronously,
 * keeping assertions straightforward.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetrySchedulerTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private TicketProcessingService ticketProcessingService;

    @Mock
    private TaskExecutor taskExecutor;

    private RetryScheduler retryScheduler;
    private SimpleMeterRegistry meterRegistry;

    private static final int MAX_RETRIES = 5;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // Run submitted Runnables immediately so process() is called synchronously in tests
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(taskExecutor).execute(any());
        retryScheduler = new RetryScheduler(
                emailTicketRepository, ticketProcessingService, taskExecutor, meterRegistry);
    }

    private EmailTicket buildRetryTicket(Long id, int retryCount) {
        EmailTicket ticket = new EmailTicket();
        ticket.setId(id);
        ticket.setMessageId("msg-" + id + "@test.com");
        ticket.setStatus(TicketStatus.RETRY_LATER);
        ticket.setRetryCount(retryCount);
        return ticket;
    }

    /**
     * Single RETRY_LATER ticket — retryCount incremented, status set to NEW, process() called.
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
     * Multiple tickets — all processed, counter matches ticket count.
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
     * Empty queue — scheduler exits silently without touching anything.
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
     * Repository is queried with exactly RETRY_LATER and MAX_RETRIES = 5.
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
     * retryCount is persisted BEFORE process() is invoked — protects against infinite loops
     * if process() throws and the ticket ends up back in RETRY_LATER.
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
