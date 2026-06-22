package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.controller.TicketApiController;
import com.smartfreight.inbox.controller.TicketApiController.AssignRequest;
import com.smartfreight.inbox.controller.TicketApiController.AttachRequest;
import com.smartfreight.inbox.controller.TicketApiController.DraftRequest;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-tenant authorization (IDOR) coverage for the ticket API — an automated mini-pentest on the
 * platform's headline risk: an operator of tenant A must never be able to read or mutate a ticket
 * owned by tenant B by guessing its id (ROADMAP 4.7 pre-pentest hardening).
 *
 * <p>This is the end-to-end wiring the existing tests cover only in halves:
 * {@link TenantFilterTest} proves the {@code Jwt} {@code tenant_id} claim maps onto
 * {@link TenantContext} (no DB), and {@link TenantIsolationTest} proves {@code TenantContext} →
 * Postgres RLS isolates at the repository layer (no controller). Here the REAL
 * {@link TicketApiController} runs inside the attacker tenant's context against a real RLS-backed
 * database, so the full chain (tenant scope → {@code requireTicket} → {@code findById} → RLS) is
 * exercised: every id-bearing endpoint must answer {@code 404 NOT FOUND} for a foreign ticket
 * (the row is invisible, so the controller can't even reach its mutation), and the victim's row
 * must survive untouched.
 *
 * <p>Harness mirrors {@link TenantIsolationTest}: NOT {@code @Transactional} (Hibernate fixes the
 * session tenant at session open, so one test-wide transaction could never switch tenants),
 * connecting as {@code smartfreight_app} (RLS does not bind the container superuser), cleanup is a
 * hard DELETE under the system context.
 *
 * <p>First slice covers {@link TicketApiController} (the richest, most sensitive surface). The
 * other id-bearing controllers ({@code /api/documents/{id}/download}, {@code /api/rates/{id}},
 * {@code /api/training-examples/{id}}) are the next slices of the same hardening item.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CrossTenantApiAccessTest {

    /** The Default tenant (V18) plays the attacker — it tries to reach the victim's ticket. */
    private static final long ATTACKER_TENANT = 1L;

    @Autowired
    private TicketApiController tickets;

    @Autowired
    private EmailTicketRepository ticketRepository;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private JdbcTemplate jdbc;

    private Long victimTenant;
    private Long victimTicketId;

    @BeforeEach
    void seedVictimTicket() {
        victimTenant = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("victim-" + UUID.randomUUID()).build()).getId());
        victimTicketId = TenantContext.callAs(victimTenant, () -> ticketRepository.save(
                EmailTicket.builder()
                        .messageId("victim-" + UUID.randomUUID() + "@test")
                        .senderEmail("customer@victim.example")
                        .subject("victim ticket")
                        .body("confidential body")
                        .draftReply("victim draft")
                        .status(TicketStatus.DRAFT_READY)
                        .build()).getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM ticket_audit_log WHERE ticket_id = ?", victimTicketId);
            jdbc.update("DELETE FROM email_ticket WHERE id = ?", victimTicketId);
            tenants.deleteById(victimTenant);
        });
    }

    // ---- positive control: the owner CAN reach its own ticket (the 404s below aren't trivial) ----

    @Test
    void owner_canReadOwnTicket() {
        var dto = TenantContext.callAs(victimTenant, () -> tickets.detail(victimTicketId));
        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(victimTicketId);
    }

    // ---- attacker (tenant A) probing the victim's (tenant B) ticket id: every endpoint → 404 ----

    @Test
    void attacker_cannotReadForeignTicket() {
        assertCrossTenant404(() -> tickets.detail(victimTicketId));
    }

    @Test
    void attacker_cannotSendOrEditForeignDraft() {
        assertCrossTenant404(() -> tickets.updateDraft(victimTicketId, new DraftRequest("send", "HACKED")));
    }

    @Test
    void attacker_cannotCloseForeignTicket() {
        assertCrossTenant404(() -> tickets.close(victimTicketId));
    }

    @Test
    void attacker_cannotDeleteForeignTicket() {
        assertCrossTenant404(() -> tickets.delete(victimTicketId));
    }

    @Test
    void attacker_cannotAttachToForeignTicket() {
        assertCrossTenant404(() -> tickets.attachDocument(victimTicketId, new AttachRequest(null)));
    }

    @Test
    void attacker_cannotAssignForeignTicket() {
        assertCrossTenant404(() -> tickets.assign(victimTicketId, new AssignRequest("attacker-user"), attackerJwt()));
    }

    @Test
    void attacker_cannotUnassignForeignTicket() {
        assertCrossTenant404(() -> tickets.unassign(victimTicketId));
    }

    // ---- after every mutation attempt, the victim's row must be byte-for-byte untouched ----

    @Test
    void victimTicket_survivesEveryCrossTenantMutationAttempt() {
        assertCrossTenant404(() -> tickets.updateDraft(victimTicketId, new DraftRequest("send", "HACKED")));
        assertCrossTenant404(() -> tickets.close(victimTicketId));
        assertCrossTenant404(() -> tickets.attachDocument(victimTicketId, new AttachRequest(null)));
        assertCrossTenant404(() -> tickets.assign(victimTicketId, new AssignRequest("attacker-user"), attackerJwt()));
        assertCrossTenant404(() -> tickets.unassign(victimTicketId));
        assertCrossTenant404(() -> tickets.delete(victimTicketId));

        // Read the raw row under the system context (bypasses RLS, sees the physical row even if a
        // soft delete had slipped through) — proves nothing changed.
        Map<String, Object> row = TenantContext.callAsSystem(() -> jdbc.queryForMap(
                "SELECT status, draft_reply, deleted, assignee_user_id FROM email_ticket WHERE id = ?",
                victimTicketId));
        assertThat(row.get("status")).isEqualTo(TicketStatus.DRAFT_READY.name());
        assertThat(row.get("draft_reply")).isEqualTo("victim draft");
        assertThat(row.get("deleted")).isEqualTo(false);
        assertThat(row.get("assignee_user_id")).isNull();
    }

    // ---- helpers ----

    /** Runs the controller call inside the attacker's tenant scope and asserts a 404 NOT FOUND. */
    private void assertCrossTenant404(Runnable controllerCall) {
        assertThatThrownBy(() -> TenantContext.runAs(ATTACKER_TENANT, controllerCall))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** A bearer principal for the attacker tenant — the assign endpoint reads it after the id check. */
    private Jwt attackerJwt() {
        return Jwt.withTokenValue("token").header("alg", "none")
                .subject("attacker-user").claim("tenant_id", ATTACKER_TENANT).build();
    }
}
