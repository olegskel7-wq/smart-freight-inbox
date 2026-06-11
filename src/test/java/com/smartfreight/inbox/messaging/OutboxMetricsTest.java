package com.smartfreight.inbox.messaging;

import com.smartfreight.inbox.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for OutboxMetrics gauges: values come straight from the repository
 * at sample time, and the age gauge reports 0 for a drained outbox.
 */
@ExtendWith(MockitoExtension.class)
class OutboxMetricsTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        new OutboxMetrics(outboxEventRepository, meterRegistry);
    }

    @Test
    void pendingGauge_reflectsRepositoryCount() {
        when(outboxEventRepository.countByPublishedAtIsNull()).thenReturn(7L);

        double value = meterRegistry.get("smartfreight_outbox_pending").gauge().value();

        assertThat(value).isEqualTo(7.0);
    }

    @Test
    void ageGauge_zeroWhenOutboxDrained() {
        when(outboxEventRepository.findOldestUnpublishedCreatedAt()).thenReturn(null);

        double value = meterRegistry.get("smartfreight_outbox_oldest_pending_age_seconds")
                .gauge().value();

        assertThat(value).isEqualTo(0.0);
    }

    @Test
    void ageGauge_reportsAgeOfOldestPendingEvent() {
        when(outboxEventRepository.findOldestUnpublishedCreatedAt())
                .thenReturn(LocalDateTime.now().minusSeconds(120));

        double value = meterRegistry.get("smartfreight_outbox_oldest_pending_age_seconds")
                .gauge().value();

        assertThat(value).isBetween(119.0, 125.0);
    }
}
