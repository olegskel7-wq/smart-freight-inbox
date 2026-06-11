package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SLA deadline calculation (Phase 1.5).
 *
 * Uses a real SlaProperties instance configured like application.yaml — the binding
 * itself is Spring's job, what we pin down is the window lookup and the null-type
 * (unclassified → GENERAL) contract.
 */
@ExtendWith(MockitoExtension.class)
class SlaServiceTest {

    private static final LocalDateTime RECEIVED = LocalDateTime.of(2026, 6, 11, 12, 0);

    @Mock
    private SettingsService settingsService;

    private SlaService slaService;

    @BeforeEach
    void setUp() {
        SlaProperties properties = new SlaProperties();
        properties.getMinutes().put(TicketType.RFQ, 120L);
        properties.getMinutes().put(TicketType.BOOKING_CONFIRM, 30L);
        properties.setDefaultMinutes(480L);
        slaService = new SlaService(settingsService, properties);
    }

    @Test
    void deadlineFor_configuredType_usesItsWindow() {
        assertThat(slaService.deadlineFor(TicketType.RFQ, RECEIVED))
                .isEqualTo(RECEIVED.plusMinutes(120));
        assertThat(slaService.deadlineFor(TicketType.BOOKING_CONFIRM, RECEIVED))
                .isEqualTo(RECEIVED.plusMinutes(30));
    }

    @Test
    void deadlineFor_unconfiguredType_fallsBackToDefault() {
        assertThat(slaService.deadlineFor(TicketType.INVOICE_QUERY, RECEIVED))
                .isEqualTo(RECEIVED.plusMinutes(480));
    }

    @Test
    void deadlineFor_nullType_treatedAsGeneral() {
        // GENERAL is not in the map → default window; null must behave identically.
        assertThat(slaService.deadlineFor(null, RECEIVED))
                .isEqualTo(slaService.deadlineFor(TicketType.GENERAL, RECEIVED));
    }

    @Test
    void isEnabled_delegatesToFeatureFlag() {
        when(settingsService.isFeatureEnabled(SlaService.FEATURE_KEY, true)).thenReturn(false);

        assertThat(slaService.isEnabled()).isFalse();
    }
}
