package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.WebhookDelivery;
import com.smartfreight.inbox.entity.WebhookDeliveryStatus;
import com.smartfreight.inbox.entity.WebhookSubscription;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.repository.WebhookDeliveryRepository;
import com.smartfreight.inbox.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.5 — proves webhook_subscription / webhook_delivery isolation on a real database, and pins the
 * dispatcher's native {@code lockDueBatch} query (which a mock cannot exercise). A subscription one
 * tenant records is invisible to others through both layers (Hibernate {@code @TenantId} for entity
 * reads, the V30 RLS policy for raw SQL); the system context (the dispatcher) sees across all tenants
 * but only picks up due PENDING rows.
 *
 * <p>Same shape as {@code QuoteFollowUpTenantIsolationTest}: NOT {@code @Transactional} (Hibernate fixes
 * the session tenant at session open), cleanup is a hard DELETE under the system context, and assertions
 * are scoped by a per-run marker so cached-context rows from other tests cannot interfere.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WebhookTenantIsolationTest {

    private static final long DEFAULT_TENANT = 1L;
    private static final String MARK = "iso.test";

    @Autowired
    private WebhookSubscriptionRepository subscriptions;

    @Autowired
    private WebhookDeliveryRepository deliveries;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private JdbcTemplate jdbc;

    private Long secondTenant;

    @BeforeEach
    void createSecondTenant() {
        secondTenant = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("wh-" + UUID.randomUUID()).build()).getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            // Deliveries cascade-delete with their subscription.
            jdbc.update("DELETE FROM webhook_subscription WHERE url LIKE '%" + MARK + "%'");
            tenants.deleteById(secondTenant);
        });
    }

    private Long createSubscription(Long tenant) {
        return TenantContext.callAs(tenant, () -> {
            WebhookSubscription s = new WebhookSubscription();
            s.setUrl("https://" + UUID.randomUUID() + "." + MARK + "/hook");
            s.setSecretEnc("enc");
            s.setEventTypes("*");
            s.setEnabled(true);
            return subscriptions.save(s).getId();
        });
    }

    private UUID createDelivery(Long tenant, Long subscriptionId,
                               WebhookDeliveryStatus status, LocalDateTime nextAttemptAt) {
        return TenantContext.callAs(tenant, () -> {
            WebhookDelivery d = new WebhookDelivery();
            d.setSubscriptionId(subscriptionId);
            d.setEventId(UUID.randomUUID());
            d.setEventType("ticket.created");
            d.setPayload("{}");
            d.setStatus(status);
            d.setNextAttemptAt(nextAttemptAt);
            return deliveries.save(d).getEventId();
        });
    }

    @Test
    void subscription_isInvisibleToOtherTenants_viaEntityQuery() {
        createSubscription(secondTenant);

        List<WebhookSubscription> tenant1View =
                TenantContext.callAs(DEFAULT_TENANT, subscriptions::findByEnabledTrue);
        List<WebhookSubscription> tenant2View =
                TenantContext.callAs(secondTenant, subscriptions::findByEnabledTrue);

        assertThat(tenant1View).noneMatch(s -> s.getUrl().contains(MARK));
        assertThat(tenant2View).anyMatch(s -> s.getUrl().contains(MARK));
    }

    @Test
    void nativeSql_isGuardedByRls() {
        createSubscription(secondTenant);

        Long visibleToTenant1 = TenantContext.callAs(DEFAULT_TENANT, () -> jdbc.queryForObject(
                "SELECT count(*) FROM webhook_subscription WHERE url LIKE '%" + MARK + "%'", Long.class));
        Long visibleToSystem = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT count(*) FROM webhook_subscription WHERE url LIKE '%" + MARK + "%'", Long.class));

        assertThat(visibleToTenant1).isZero();
        assertThat(visibleToSystem).isEqualTo(1L);
    }

    @Test
    void lockDueBatch_returnsOnlyDuePendingAcrossTenants() {
        Long sub1 = createSubscription(DEFAULT_TENANT);
        Long sub2 = createSubscription(secondTenant);

        UUID dueTenant1 = createDelivery(DEFAULT_TENANT, sub1,
                WebhookDeliveryStatus.PENDING, LocalDateTime.now().minusMinutes(1));
        UUID dueTenant2 = createDelivery(secondTenant, sub2,
                WebhookDeliveryStatus.PENDING, LocalDateTime.now().minusMinutes(1));
        UUID alreadyDelivered = createDelivery(secondTenant, sub2,
                WebhookDeliveryStatus.DELIVERED, LocalDateTime.now().minusMinutes(1));
        UUID notYetDue = createDelivery(secondTenant, sub2,
                WebhookDeliveryStatus.PENDING, LocalDateTime.now().plusHours(1));

        List<UUID> picked = TenantContext.callAsSystem(
                () -> deliveries.lockDueBatch(LocalDateTime.now(), 100).stream()
                        .map(WebhookDelivery::getEventId).toList());

        // The dispatcher works cross-tenant under the system context...
        assertThat(picked).contains(dueTenant1, dueTenant2);
        // ...but never re-sends a delivered row or one whose backoff has not elapsed.
        assertThat(picked).doesNotContain(alreadyDelivered, notYetDue);
    }

    @Test
    void delivery_isInvisibleToOtherTenants_viaEntityQuery() {
        Long sub2 = createSubscription(secondTenant);
        createDelivery(secondTenant, sub2, WebhookDeliveryStatus.PENDING, LocalDateTime.now());

        long tenant1Count = TenantContext.callAs(DEFAULT_TENANT,
                () -> deliveries.findBySubscriptionIdOrderByIdDesc(sub2,
                        org.springframework.data.domain.PageRequest.of(0, 50)).size());
        long tenant2Count = TenantContext.callAs(secondTenant,
                () -> deliveries.findBySubscriptionIdOrderByIdDesc(sub2,
                        org.springframework.data.domain.PageRequest.of(0, 50)).size());

        assertThat(tenant1Count).isZero();
        assertThat(tenant2Count).isEqualTo(1L);
    }
}
