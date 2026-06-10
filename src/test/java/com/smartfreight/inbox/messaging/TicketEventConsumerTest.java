package com.smartfreight.inbox.messaging;

import com.smartfreight.inbox.service.TicketProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for TicketEventConsumer (Phase 1.3).
 *
 * Verifies:
 * - a valid event message triggers the AI pipeline for the right ticket
 * - an unparseable payload throws (→ retry advice → DLQ), without touching the pipeline
 */
@ExtendWith(MockitoExtension.class)
class TicketEventConsumerTest {

    @Mock
    private TicketProcessingService ticketProcessingService;

    private TicketEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TicketEventConsumer(ticketProcessingService, JsonMapper.builder().build());
    }

    @Test
    void onTicketEvent_validPayload_runsPipelineForTicket() throws Exception {
        consumer.onTicketEvent(
                "{\"ticketId\":42,\"eventType\":\"ticket.created\",\"occurredAt\":\"2026-06-10T12:00:00Z\"}");

        verify(ticketProcessingService).process(42L);
    }

    @Test
    void onTicketEvent_malformedPayload_throwsForDlqRouting() {
        assertThatThrownBy(() -> consumer.onTicketEvent("not-json-at-all"))
                .isInstanceOf(Exception.class);

        verifyNoInteractions(ticketProcessingService);
    }
}
