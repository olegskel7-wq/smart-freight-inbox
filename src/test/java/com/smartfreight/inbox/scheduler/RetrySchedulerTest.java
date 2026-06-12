package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.messaging.TicketEventPublisher;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RetryScheduler.
 *
 * Verifies:
 * - RETRY_LATER tickets with retryCount < MAX_RETRIES are re-enqueued
 * - retryCount and status are updated before the retry event is published
 * - Micrometer counter is incremented per ticket
 * - Correct behaviour when no tickets are eligible
 *
 * Since Phase 1.3 the scheduler no longer runs the pipeline itself — it records a
 * "ticket.retry-requested" event via TicketEventPublisher (transactional outbox)
 * and the RabbitMQ consumer does the heavy lifting. Tests assert the publisher call.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetrySchedulerTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private TicketEventPublisher ticketEventPublisher;

    private RetryScheduler retryScheduler;
    private SimpleMeterRegistry meterRegistry;
    private CircuitBreaker ollamaCircuitBreaker;

    private static final int MAX_RETRIES = 5;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // Real (default-config) breaker — CLOSED, so re-enqueueing proceeds unless a test opens it.
        ollamaCircuitBreaker = CircuitBreaker.ofDefaults("ollama-test");
        retryScheduler = new RetryScheduler(
                emailTicketRepository, ticketEventPublisher, ollamaCircuitBreaker, meterRegistry);
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
     * Single RETRY_LATER ticket — retryCount incremented, status set to NEW,
     * retry event published.
     */
    @Test
    void retryFailedTickets_oneEligibleTicket_reEnqueuesAndUpdatesFields() {
        EmailTicket ticket = buildRetryTicket(1L, 2);
        when(emailTicketRepository.findByStatusAndRetryCountLessThan(
                eq(TicketStatus.RETRY_LATER), eq(MAX_RETRIES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ticket)));

        retryScheduler.retryFailedTickets();

        ArgumentCaptor<EmailTicket> captor = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(captor.capture());

        EmailTicket saved = captor.getValue();
        assertThat(saved.getRetryCount()).isEqualTo(3);
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.NEW);

        verify(ticketEventPublisher).publishRetryRequested(1L);
    }

    /**
     * Multiple tickets — all re-enqueued, counter matches ticket count.
     */
    @Test
    void retryFailedTickets_multipleTickets_reEnqueuesAllAndIncrementsCounter() {
        EmailTicket t1 = buildRetryTicket(1L, 0);
        EmailTicket t2 = buildRetryTicket(2L, 1);
        EmailTicket t3 = buildRetryTicket(3L, 3);

        when(emailTicketRepository.findByStatusAndRetryCountLessThan(
                eq(TicketStatus.RETRY_LATER), eq(MAX_RETRIES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(t1, t2, t3)));

        retryScheduler.retryFailedTickets();

        verify(ticketEventPublisher).publishRetryRequested(1L);
        verify(ticketEventPublisher).publishRetryRequested(2L);
        verify(ticketEventPublisher).publishRetryRequested(3L);

        double counterValue = meterRegistry.counter("smartfreight_tickets_retry_count_total").count();
        assertThat(counterValue).isEqualTo(3.0);
    }

    /**
     * Empty queue — scheduler exits silently without touching anything.
     */
    @Test
    void retryFailedTickets_noTickets_doesNothing() {
        when(emailTicketRepository.findByStatusAndRetryCountLessThan(
                eq(TicketStatus.RETRY_LATER), eq(MAX_RETRIES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        retryScheduler.retryFailedTickets();

        verifyNoInteractions(ticketEventPublisher);
        verify(emailTicketRepository, never()).save(any());
    }

    /**
     * Repository is queried with exactly RETRY_LATER, MAX_RETRIES = 5 and a page
     * request that puts the batch limit into the SQL: page size = batchSize (10),
     * ordered oldest updatedAt first (FIFO — fresh parkings cannot starve old ones).
     */
    @Test
    void retryFailedTickets_queriesRepositoryWithBatchLimitAndFifoOrder() {
        when(emailTicketRepository.findByStatusAndRetryCountLessThan(
                eq(TicketStatus.RETRY_LATER), eq(MAX_RETRIES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        retryScheduler.retryFailedTickets();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(emailTicketRepository).findByStatusAndRetryCountLessThan(
                eq(TicketStatus.RETRY_LATER), eq(MAX_RETRIES), pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort().getOrderFor("updatedAt"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.ASC);
    }

    /**
     * Phase 1.7: while the Ollama circuit breaker is OPEN the scheduler skips the whole
     * run — parked tickets keep their retry budget until the dependency recovers.
     */
    @Test
    void retryFailedTickets_ollamaBreakerOpen_skipsRunEntirely() {
        ollamaCircuitBreaker.transitionToOpenState();

        retryScheduler.retryFailedTickets();

        verifyNoInteractions(emailTicketRepository, ticketEventPublisher);
    }

    /**
     * retryCount is persisted BEFORE the retry event is published — protects against
     * infinite loops if processing fails and the ticket ends up back in RETRY_LATER.
     */
    @Test
    void retryFailedTickets_incrementsRetryCountBeforePublishing() {
        EmailTicket ticket = buildRetryTicket(5L, 1);
        when(emailTicketRepository.findByStatusAndRetryCountLessThan(
                eq(TicketStatus.RETRY_LATER), eq(MAX_RETRIES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ticket)));

        var inOrder = inOrder(emailTicketRepository, ticketEventPublisher);

        retryScheduler.retryFailedTickets();

        inOrder.verify(emailTicketRepository).save(any(EmailTicket.class));
        inOrder.verify(ticketEventPublisher).publishRetryRequested(5L);
    }
}
