package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.QuoteFollowUp;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.QuoteFollowUpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link QuoteFollowUpService} — the sent-quote → follow-up arming (Phase 2.5).
 */
@ExtendWith(MockitoExtension.class)
class QuoteFollowUpServiceTest {

    @Mock
    private QuoteFollowUpRepository followUpRepository;

    private QuoteFollowUpService service;

    @BeforeEach
    void setUp() {
        service = new QuoteFollowUpService(followUpRepository);
    }

    private EmailTicket ticket(Long id, TicketType type, boolean proactive, String sender) {
        EmailTicket t = new EmailTicket();
        t.setId(id);
        t.setTicketType(type);
        t.setProactive(proactive);
        t.setSenderEmail(sender);
        return t;
    }

    @Test
    void armsNewFollowUp_whenNoneExists() {
        when(followUpRepository.findByTicketId(7L)).thenReturn(Optional.empty());

        service.armFromTicket(ticket(7L, TicketType.RFQ, false, "customer@acme.com"));

        ArgumentCaptor<QuoteFollowUp> captor = ArgumentCaptor.forClass(QuoteFollowUp.class);
        verify(followUpRepository).save(captor.capture());
        QuoteFollowUp saved = captor.getValue();
        assertThat(saved.getTicketId()).isEqualTo(7L);
        assertThat(saved.getCustomerEmail()).isEqualTo("customer@acme.com");
        assertThat(saved.getQuoteSentAt()).isNotNull();
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void refreshesAndReactivatesExistingFollowUp_onReQuote() {
        QuoteFollowUp existing = new QuoteFollowUp();
        existing.setTicketId(7L);
        existing.setCustomerEmail("customer@acme.com");
        existing.setActive(false); // previously nudged/closed
        when(followUpRepository.findByTicketId(7L)).thenReturn(Optional.of(existing));

        service.armFromTicket(ticket(7L, TicketType.RFQ, false, "customer@acme.com"));

        verify(followUpRepository).save(existing);
        assertThat(existing.isActive()).isTrue();
        assertThat(existing.getQuoteSentAt()).isNotNull();
    }

    @Test
    void extractsBareAddressFromRfcFormattedSender() {
        when(followUpRepository.findByTicketId(7L)).thenReturn(Optional.empty());

        service.armFromTicket(ticket(7L, TicketType.RFQ, false, "Jane Doe <jane@acme.com>"));

        ArgumentCaptor<QuoteFollowUp> captor = ArgumentCaptor.forClass(QuoteFollowUp.class);
        verify(followUpRepository).save(captor.capture());
        assertThat(captor.getValue().getCustomerEmail()).isEqualTo("jane@acme.com");
    }

    @Test
    void skips_whenNotRfq() {
        service.armFromTicket(ticket(7L, TicketType.STATUS_UPDATE, false, "customer@acme.com"));

        verifyNoInteractions(followUpRepository);
    }

    @Test
    void skips_whenProactive() {
        // A proactive nudge is itself type RFQ — the proactive guard stops a nudge arming a nudge.
        service.armFromTicket(ticket(7L, TicketType.RFQ, true, "customer@acme.com"));

        verifyNoInteractions(followUpRepository);
    }

    @Test
    void skips_whenSenderBlank() {
        service.armFromTicket(ticket(7L, TicketType.RFQ, false, null));

        verifyNoInteractions(followUpRepository);
    }
}
