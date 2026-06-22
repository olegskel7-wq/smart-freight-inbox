package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.controller.DocumentApiController;
import com.smartfreight.inbox.controller.RateApiController;
import com.smartfreight.inbox.controller.TrainingExampleApiController;
import com.smartfreight.inbox.entity.CarrierRate;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.TrainingExample;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.CarrierRateRepository;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.repository.TrainingExampleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-tenant authorization (IDOR) coverage for the per-tenant data-store APIs — slice 2 of the
 * ROADMAP 4.7 pre-pentest hardening, after {@link CrossTenantApiAccessTest} (tickets). An operator
 * of tenant A must never reach tenant B's stored documents, carrier rates or captured corrections
 * by guessing a row id.
 *
 * <p>These controllers reach the row through {@code findById}/{@code deleteById} rather than the
 * ticket controller's explicit {@code requireTicket}, so the failure shapes differ and are asserted
 * accordingly:
 * <ul>
 *   <li><b>download</b> ({@code GET /api/documents/{id}/download}) returns
 *       {@code 404 NOT FOUND} with an empty body — the highest-value check, it streams raw file
 *       bytes;</li>
 *   <li><b>delete</b> ({@code DELETE} on documents/rates/training-examples) cannot touch a foreign
 *       row: under RLS the {@code findById} inside {@code deleteById} sees nothing, so the call
 *       either no-ops or throws — either way the victim's row must survive (asserted via a
 *       system-context count, the property a pentester actually cares about, independent of the
 *       exception type);</li>
 *   <li><b>clear-all</b> ({@code DELETE /api/rates}) is tenant-scoped — one tenant wiping its own
 *       sheet must leave every other tenant's rates intact.</li>
 * </ul>
 *
 * <p>Harness mirrors {@link CrossTenantApiAccessTest}: full context against a real RLS-backed
 * database as {@code smartfreight_app}, NOT {@code @Transactional}, hard-delete cleanup under the
 * system context.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CrossTenantStoreApiAccessTest {

    /** The Default tenant (V18) plays the attacker. */
    private static final long ATTACKER_TENANT = 1L;

    @Autowired private DocumentApiController documents;
    @Autowired private RateApiController rates;
    @Autowired private TrainingExampleApiController trainingExamples;
    @Autowired private TenantDocumentRepository documentRepository;
    @Autowired private CarrierRateRepository rateRepository;
    @Autowired private TrainingExampleRepository trainingExampleRepository;
    @Autowired private TenantRepository tenants;
    @Autowired private JdbcTemplate jdbc;

    private static final byte[] DOC_BYTES = {10, 20, 30, 40, 50};

    private Long victimTenant;
    private Long victimDocId;
    private Long victimRateId;
    private Long victimExampleId;

    @BeforeEach
    void seedVictimRows() {
        victimTenant = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("victim-" + UUID.randomUUID()).build()).getId());

        TenantContext.runAs(victimTenant, () -> {
            TenantDocument doc = new TenantDocument();
            doc.setIdentifier("VICTIM-SHIP-1");
            doc.setDocType(DocumentType.POD);
            doc.setFilename("secret-pod.pdf");
            doc.setContentType("application/pdf");
            doc.setContent(DOC_BYTES);
            doc.setFileSize(DOC_BYTES.length);
            victimDocId = documentRepository.save(doc).getId();

            CarrierRate rate = new CarrierRate();
            rate.setCarrier("VictimCarrier");
            rate.setOrigin("Felixstowe");
            rate.setDestination("Southampton");
            rate.setRate(new BigDecimal("999.00"));
            victimRateId = rateRepository.save(rate).getId();

            TrainingExample ex = new TrainingExample();
            ex.setTicketType(TicketType.RFQ);
            ex.setEnquiryText("victim enquiry");
            ex.setAiDraft("victim ai draft");
            ex.setOperatorFinal("victim operator final");
            victimExampleId = trainingExampleRepository.save(ex).getId();
        });
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM tenant_document WHERE tenant_id = ?", victimTenant);
            jdbc.update("DELETE FROM carrier_rate WHERE tenant_id = ?", victimTenant);
            jdbc.update("DELETE FROM training_example WHERE tenant_id = ?", victimTenant);
            tenants.deleteById(victimTenant);
        });
    }

    // ---- positive control: the owner CAN download its own document ----

    @Test
    void owner_canDownloadOwnDocument() {
        ResponseEntity<ByteArrayResource> resp =
                TenantContext.callAs(victimTenant, () -> documents.download(victimDocId));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getByteArray()).isEqualTo(DOC_BYTES);
    }

    // ---- attacker cannot read another tenant's raw file bytes ----

    @Test
    void attacker_cannotDownloadForeignDocument() {
        ResponseEntity<ByteArrayResource> resp =
                TenantContext.callAs(ATTACKER_TENANT, () -> documents.download(victimDocId));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNull();
    }

    // ---- attacker cannot delete another tenant's rows (the victim row must survive) ----

    @Test
    void attacker_cannotDeleteForeignDocument() {
        attemptIgnoringError(() -> documents.delete(victimDocId));
        assertThat(rowExists("tenant_document", victimDocId)).isTrue();
    }

    @Test
    void attacker_cannotDeleteForeignRate() {
        attemptIgnoringError(() -> rates.delete(victimRateId));
        assertThat(rowExists("carrier_rate", victimRateId)).isTrue();
    }

    @Test
    void attacker_cannotDeleteForeignTrainingExample() {
        attemptIgnoringError(() -> trainingExamples.delete(victimExampleId));
        assertThat(rowExists("training_example", victimExampleId)).isTrue();
    }

    // ---- one tenant clearing its own rate sheet must not wipe another tenant's rates ----

    @Test
    void attacker_clearAllRates_leavesVictimRatesIntact() {
        attemptIgnoringError(rates::clearAll);
        assertThat(rowExists("carrier_rate", victimRateId)).isTrue();
    }

    // ---- helpers ----

    /**
     * Runs a controller mutation inside the attacker's tenant scope, swallowing any error. Spring
     * Data's {@code deleteById} may throw when the (RLS-hidden) row is not found OR silently no-op,
     * depending on the version — both are acceptable; the meaningful assertion is that the victim's
     * row still exists afterward.
     */
    private void attemptIgnoringError(Runnable controllerCall) {
        try {
            TenantContext.runAs(ATTACKER_TENANT, controllerCall);
        } catch (RuntimeException ignored) {
            // Expected for a foreign/absent id — no row was (or could be) deleted under RLS.
        }
    }

    /** True if the row physically exists, read under the system context (bypasses RLS). */
    private boolean rowExists(String table, Long id) {
        Long count = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id));
        return count != null && count == 1L;
    }
}
