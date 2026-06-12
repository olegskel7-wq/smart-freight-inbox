package com.smartfreight.inbox.messaging;

import com.smartfreight.inbox.service.TicketProcessingService;
import com.smartfreight.inbox.tenancy.TenancyProperties;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for TicketEventConsumer (Phase 1.3).
 *
 * Verifies:
 * - a valid event message triggers the AI pipeline for the right ticket
 * - an unparseable payload throws (→ retry advice → DLQ), without touching the pipeline
 * - the pipeline runs under the event's tenant context (Phase 2.2), with the
 *   default-tenant fallback for pre-2.2 messages that carry no tenantId
 */
@ExtendWith(MockitoExtension.class)
class TicketEventConsumerTest {

    @Mock
    private TicketProcessingService ticketProcessingService;

    private TicketEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TicketEventConsumer(
                ticketProcessingService, JsonMapper.builder().build(), new TenancyProperties());
    }

    @Test
    void onTicketEvent_validPayload_runsPipelineForTicketUnderItsTenant() throws Exception {
        // Capture the tenant the pipeline actually runs under, not just the call.
        Long[] seenTenant = new Long[1];
        doAnswer(inv -> {
            seenTenant[0] = TenantContext.tenantIdOrNull();
            return null;
        }).when(ticketProcessingService).process(42L);

        consumer.onTicketEvent(
                "{\"ticketId\":42,\"tenantId\":7,\"eventType\":\"ticket.created\",\"occurredAt\":\"2026-06-10T12:00:00Z\"}");

        verify(ticketProcessingService).process(42L);
        assertThat(seenTenant[0]).isEqualTo(7L);
    }

    @Test
    void onTicketEvent_pre22PayloadWithoutTenant_fallsBackToDefaultTenant() throws Exception {
        Long[] seenTenant = new Long[1];
        doAnswer(inv -> {
            seenTenant[0] = TenantContext.tenantIdOrNull();
            return null;
        }).when(ticketProcessingService).process(42L);

        consumer.onTicketEvent(
                "{\"ticketId\":42,\"eventType\":\"ticket.created\",\"occurredAt\":\"2026-06-10T12:00:00Z\"}");

        verify(ticketProcessingService).process(42L);
        assertThat(seenTenant[0]).isEqualTo(new TenancyProperties().getDefaultId());
    }

    @Test
    void onTicketEvent_malformedPayload_throwsForDlqRouting() {
        assertThatThrownBy(() -> consumer.onTicketEvent("not-json-at-all"))
                .isInstanceOf(Exception.class);

        verifyNoInteractions(ticketProcessingService);
    }
}
