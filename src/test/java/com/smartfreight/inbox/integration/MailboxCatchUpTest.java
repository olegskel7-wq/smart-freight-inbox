package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.SearchTerm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MailboxCatchUp} (Gate B5): only emails inside the window, newer than the
 * mailbox's connection, and without a ticket are re-ingested; the folder is opened READ-ONLY.
 * The dedup query's tenant scoping and soft-delete visibility are proven on Postgres in
 * {@code TicketMailboxTenantIsolationTest}.
 */
class MailboxCatchUpTest {

    private static final Instant NOW = Instant.now();

    private EmailTicketRepository tickets;
    private MailboxImapAdapterManager adapterManager;
    private SimpleMeterRegistry meters;
    private MailboxCatchUp catchUp;
    private Folder inbox;
    private Store store;

    @BeforeEach
    void setUp() throws Exception {
        tickets = mock(EmailTicketRepository.class);
        adapterManager = mock(MailboxImapAdapterManager.class);
        when(adapterManager.ingest(anyLong(), anyLong(), any())).thenReturn(true);
        meters = new SimpleMeterRegistry();
        catchUp = new MailboxCatchUp(tickets, adapterManager, meters, 48);
        inbox = mock(Folder.class);
        store = mock(Store.class);
        when(store.getFolder("INBOX")).thenReturn(inbox);
    }

    private static TenantMailbox mailbox(Instant connectedAt) {
        TenantMailbox m = new TenantMailbox();
        m.setId(6L);
        m.setTenantId(90001L);
        m.setCreatedAt(LocalDateTime.ofInstant(connectedAt, ZoneId.systemDefault()));
        return m;
    }

    private static MimeMessage message(String messageId, Instant received) throws Exception {
        MimeMessage m = mock(MimeMessage.class);
        when(m.getHeader("Message-ID")).thenReturn(messageId == null ? null : new String[]{messageId});
        when(m.getReceivedDate()).thenReturn(Date.from(received));
        when(m.getSize()).thenReturn(4_096);
        return m;
    }

    private void inboxHolds(Message... messages) throws Exception {
        when(inbox.search(any(SearchTerm.class))).thenReturn(messages);
    }

    @Test
    void ingestsOnlyMessagesWithoutATicket_readOnly() throws Exception {
        MimeMessage lost = message("<lost@a.test>", NOW.minus(Duration.ofHours(1)));
        MimeMessage known = message("<known@a.test>", NOW.minus(Duration.ofHours(1)));
        inboxHolds(lost, known);
        when(tickets.findExistingMessageIds(any())).thenReturn(List.of("<known@a.test>"));

        int ingested = catchUp.run(mailbox(NOW.minus(Duration.ofDays(30))), store);

        assertThat(ingested).isEqualTo(1);
        verify(adapterManager).ingest(eq(90001L), eq(6L), eq(lost));
        verify(adapterManager, never()).ingest(anyLong(), anyLong(), eq(known));
        verify(inbox).open(Folder.READ_ONLY); // the client's \Seen state is theirs
        verify(inbox).fetch(any(), any(FetchProfile.class));
        verify(inbox).close(false);
        assertThat(meters.get("smartfreight_mailbox_catchup_ingested_total")
                .tags("tenant", "90001", "mailbox", "6").counter().count()).isEqualTo(1.0);
    }

    @Test
    void neverReachesBeforeTheMailboxWasConnected() throws Exception {
        // Onboarding: the client's mail from before we connected is history, not new enquiries.
        Instant connected = NOW.minus(Duration.ofHours(2));
        MimeMessage before = message("<old@a.test>", connected.minus(Duration.ofMinutes(5)));
        MimeMessage after = message("<new@a.test>", connected.plus(Duration.ofMinutes(5)));
        inboxHolds(before, after);
        when(tickets.findExistingMessageIds(any())).thenReturn(List.of());

        catchUp.run(mailbox(connected), store);

        verify(adapterManager).ingest(anyLong(), anyLong(), eq(after));
        verify(adapterManager, never()).ingest(anyLong(), anyLong(), eq(before));
    }

    @Test
    void windowStart_isTheLaterOfLookBackAndConnection() {
        assertThat(catchUp.windowStart(mailbox(NOW.minus(Duration.ofDays(30))), NOW))
                .isEqualTo(NOW.minus(Duration.ofHours(48)));
        Instant connected = NOW.minus(Duration.ofHours(3));
        assertThat(catchUp.windowStart(mailbox(connected), NOW)).isEqualTo(connected);
    }

    @Test
    void messagesWithoutMessageId_areNeitherQueriedNorIngested() throws Exception {
        inboxHolds(message(null, NOW.minus(Duration.ofHours(1))));

        assertThat(catchUp.run(mailbox(NOW.minus(Duration.ofDays(30))), store)).isZero();

        verify(tickets, never()).findExistingMessageIds(any(Collection.class));
        verify(adapterManager, never()).ingest(anyLong(), anyLong(), any());
    }

    @Test
    void failedIngestion_isNotCountedAsRecovered() throws Exception {
        MimeMessage lost = message("<lost@a.test>", NOW.minus(Duration.ofHours(1)));
        inboxHolds(lost);
        when(tickets.findExistingMessageIds(any())).thenReturn(List.of());
        when(adapterManager.ingest(anyLong(), anyLong(), eq(lost))).thenReturn(false);

        assertThat(catchUp.run(mailbox(NOW.minus(Duration.ofDays(30))), store)).isZero();
    }
}
