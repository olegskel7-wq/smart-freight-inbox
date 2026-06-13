package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.ShipmentWatch;
import com.smartfreight.inbox.entity.TrackedItem;
import com.smartfreight.inbox.repository.ShipmentWatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ShipmentWatchService} — the inbound-enquiry → subscription upsert (Phase 2.4).
 */
@ExtendWith(MockitoExtension.class)
class ShipmentWatchServiceTest {

    @Mock
    private ShipmentWatchRepository watchRepository;

    private ShipmentWatchService service;

    @BeforeEach
    void setUp() {
        service = new ShipmentWatchService(watchRepository);
    }

    private EmailTicket ticket(String sender, String identifier) {
        EmailTicket t = new EmailTicket();
        t.setSenderEmail(sender);
        t.setExtractedIdentifier(identifier);
        return t;
    }

    private TrackedItem item(String status) {
        TrackedItem i = new TrackedItem();
        i.setStatus(status);
        return i;
    }

    @Test
    void createsNewWatch_seededWithCurrentStatus_whenNoneExists() {
        when(watchRepository.findByIdentifierAndCustomerEmail("MSCU1234567", "customer@acme.com"))
                .thenReturn(Optional.empty());

        service.upsertFromTicket(ticket("customer@acme.com", "MSCU1234567"), item("AT_PORT"));

        ArgumentCaptor<ShipmentWatch> captor = ArgumentCaptor.forClass(ShipmentWatch.class);
        verify(watchRepository).save(captor.capture());
        ShipmentWatch saved = captor.getValue();
        assertEquals("MSCU1234567", saved.getIdentifier());
        assertEquals("customer@acme.com", saved.getCustomerEmail());
        // Seeded from the reactive reply so the poller does not immediately re-notify AT_PORT.
        assertEquals("AT_PORT", saved.getLastNotifiedStatus());
        assertTrue(saved.isActive());
    }

    @Test
    void reactivatesAndReseedsExistingWatch_onReEnquiry() {
        ShipmentWatch existing = new ShipmentWatch();
        existing.setIdentifier("MSCU1234567");
        existing.setCustomerEmail("customer@acme.com");
        existing.setLastNotifiedStatus("IN_TRANSIT");
        existing.setActive(false); // previously delivered
        when(watchRepository.findByIdentifierAndCustomerEmail("MSCU1234567", "customer@acme.com"))
                .thenReturn(Optional.of(existing));

        service.upsertFromTicket(ticket("customer@acme.com", "MSCU1234567"), item("RELEASED"));

        verify(watchRepository).save(existing);
        assertEquals("RELEASED", existing.getLastNotifiedStatus());
        assertTrue(existing.isActive(), "a re-enquiry reactivates a completed watch");
    }

    @Test
    void extractsBareAddressFromRfcFormattedSender() {
        when(watchRepository.findByIdentifierAndCustomerEmail(eq("MSCU1234567"), anyString()))
                .thenReturn(Optional.empty());

        service.upsertFromTicket(ticket("Jane Doe <jane@acme.com>", "MSCU1234567"), item("AT_PORT"));

        ArgumentCaptor<ShipmentWatch> captor = ArgumentCaptor.forClass(ShipmentWatch.class);
        verify(watchRepository).save(captor.capture());
        assertEquals("jane@acme.com", captor.getValue().getCustomerEmail());
    }

    @Test
    void skips_whenIdentifierMissing() {
        service.upsertFromTicket(ticket("customer@acme.com", null), item("AT_PORT"));

        verifyNoInteractions(watchRepository);
    }
}
