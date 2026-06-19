package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.support.DefaultTenantTestExecutionListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Phase 3.3 S2 shared-queue assignment queries against a real PostgreSQL
 * (Testcontainers). They pin down the {@code :status IS NULL} optional-filter trick in
 * {@link EmailTicketRepository#findByAssignee} / {@link EmailTicketRepository#findUnassigned} —
 * Hibernate's handling of a null typed-enum parameter in JPQL cannot be unit-tested against a mock.
 *
 * <p>{@code @Transactional}: every test rolls back, nothing leaks into the shared context cache.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestExecutionListeners(
        value = DefaultTenantTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@Transactional
class TicketAssignmentQueriesIntegrationTest {

    @Autowired
    private EmailTicketRepository tickets;

    private static final Pageable PAGE = PageRequest.of(0, 50);
    private static final String ALICE = "uuid-alice";
    private static final String BOB = "uuid-bob";

    @BeforeEach
    void hideBaselineData() {
        tickets.deleteAll();
    }

    private void ticket(TicketStatus status, String assignee) {
        tickets.save(EmailTicket.builder()
                .messageId(UUID.randomUUID() + "@test")
                .senderEmail("customer@example.com")
                .subject("assignment test")
                .body("test")
                .status(status)
                .assigneeUserId(assignee)
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Test
    void findByAssignee_returnsOnlyThatOperatorsTickets_anyStatus() {
        ticket(TicketStatus.DRAFT_READY, ALICE);
        ticket(TicketStatus.CLOSED, ALICE);
        ticket(TicketStatus.DRAFT_READY, BOB);
        ticket(TicketStatus.NEW, null);

        Page<EmailTicket> page = tickets.findByAssignee(ALICE, null, PAGE);

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(t -> ALICE.equals(t.getAssigneeUserId()));
    }

    @Test
    void findByAssignee_withStatusFilter_narrowsToThatStatus() {
        ticket(TicketStatus.DRAFT_READY, ALICE);
        ticket(TicketStatus.CLOSED, ALICE);

        Page<EmailTicket> page = tickets.findByAssignee(ALICE, TicketStatus.DRAFT_READY, PAGE);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
    }

    @Test
    void findUnassigned_returnsOnlyUnclaimedTickets() {
        ticket(TicketStatus.NEW, null);
        ticket(TicketStatus.DRAFT_READY, null);
        ticket(TicketStatus.DRAFT_READY, ALICE);

        Page<EmailTicket> any = tickets.findUnassigned(null, PAGE);
        assertThat(any.getTotalElements()).isEqualTo(2);
        assertThat(any.getContent()).allMatch(t -> t.getAssigneeUserId() == null);

        Page<EmailTicket> drafts = tickets.findUnassigned(TicketStatus.DRAFT_READY, PAGE);
        assertThat(drafts.getTotalElements()).isEqualTo(1);
    }
}
