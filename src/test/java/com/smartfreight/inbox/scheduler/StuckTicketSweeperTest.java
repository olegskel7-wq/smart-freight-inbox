package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for StuckTicketSweeper.
 *
 * Verifies:
 * - the bulk transition targets exactly RECEIVED + NEW + PROCESSING → RETRY_LATER
 * - the cutoff reflects the configured stuck-after-minutes threshold
 * - the swept counter increments by the number of affected rows
 * - a clean run (nothing stuck) does not touch the counter
 */
@ExtendWith(MockitoExtension.class)
class StuckTicketSweeperTest {

    private static final long STUCK_AFTER_MINUTES = 30;

    @Mock
    private EmailTicketRepository emailTicketRepository;

    private StuckTicketSweeper sweeper;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        sweeper = new StuckTicketSweeper(emailTicketRepository, meterRegistry);
        ReflectionTestUtils.setField(sweeper, "stuckAfterMinutes", STUCK_AFTER_MINUTES);
    }

    @Test
    void sweep_transitionsStaleReceivedNewAndProcessingToRetryLater() {
        when(emailTicketRepository.bulkTransitionStaleStatus(any(), any(), any())).thenReturn(2);

        sweeper.sweepStuckTickets();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<TicketStatus>> statusCaptor =
                ArgumentCaptor.forClass(Collection.class);
        verify(emailTicketRepository).bulkTransitionStaleStatus(
                statusCaptor.capture(), eq(TicketStatus.RETRY_LATER), any(LocalDateTime.class));

        assertThat(statusCaptor.getValue())
                .containsExactlyInAnyOrder(
                        TicketStatus.RECEIVED, TicketStatus.NEW, TicketStatus.PROCESSING);
    }

    @Test
    void sweep_usesConfiguredThresholdForCutoff() {
        when(emailTicketRepository.bulkTransitionStaleStatus(any(), any(), any())).thenReturn(0);

        LocalDateTime before = LocalDateTime.now().minusMinutes(STUCK_AFTER_MINUTES);
        sweeper.sweepStuckTickets();
        LocalDateTime after = LocalDateTime.now().minusMinutes(STUCK_AFTER_MINUTES);

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(emailTicketRepository).bulkTransitionStaleStatus(any(), any(), cutoffCaptor.capture());

        assertThat(cutoffCaptor.getValue()).isBetween(before, after);
    }

    @Test
    void sweep_incrementsCounterBySweptCount() {
        when(emailTicketRepository.bulkTransitionStaleStatus(any(), any(), any())).thenReturn(3);

        sweeper.sweepStuckTickets();

        assertThat(meterRegistry.counter("smartfreight_tickets_swept_total").count())
                .isEqualTo(3.0);
    }

    @Test
    void sweep_nothingStuck_counterStaysZero() {
        when(emailTicketRepository.bulkTransitionStaleStatus(any(), any(), any())).thenReturn(0);

        sweeper.sweepStuckTickets();

        assertThat(meterRegistry.counter("smartfreight_tickets_swept_total").count())
                .isEqualTo(0.0);
    }
}
