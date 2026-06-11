package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.SlaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SlaBreachScheduler (Phase 1.5).
 */
@ExtendWith(MockitoExtension.class)
class SlaBreachSchedulerTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private SlaService slaService;

    private SimpleMeterRegistry meterRegistry;
    private SlaBreachScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new SlaBreachScheduler(emailTicketRepository, slaService, meterRegistry);
    }

    @Test
    void featureEnabled_marksBreachedAndCounts() {
        when(slaService.isEnabled()).thenReturn(true);
        when(emailTicketRepository.markSlaBreached(any(), eq(SlaBreachScheduler.SLA_EXEMPT_STATUSES)))
                .thenReturn(3);

        scheduler.markBreachedTickets();

        verify(emailTicketRepository).markSlaBreached(any(), eq(SlaBreachScheduler.SLA_EXEMPT_STATUSES));
        assertThat(meterRegistry.counter("smartfreight_sla_breached_total").count()).isEqualTo(3.0);
    }

    @Test
    void nothingOverdue_counterUntouched() {
        when(slaService.isEnabled()).thenReturn(true);
        when(emailTicketRepository.markSlaBreached(any(), any())).thenReturn(0);

        scheduler.markBreachedTickets();

        assertThat(meterRegistry.counter("smartfreight_sla_breached_total").count()).isZero();
    }

    @Test
    void featureDisabled_skipsEntirely() {
        when(slaService.isEnabled()).thenReturn(false);

        scheduler.markBreachedTickets();

        verifyNoInteractions(emailTicketRepository);
    }
}
