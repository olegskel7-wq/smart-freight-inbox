package com.smartfreight.inbox.service;

import com.smartfreight.inbox.config.KeycloakAdminProperties;
import com.smartfreight.inbox.service.KeycloakAdminService.KeycloakUser;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end provisioning tests (Phase 2.3 S3): the REAL {@link KeycloakAdminService} WebClient
 * driving a REAL Keycloak 26 booted from our deployed {@code realm-smartfreight.json}.
 *
 * <p>{@link KeycloakAdminServiceTest} (MockWebServer) verifies the client speaks the right HTTP;
 * this verifies the realm import is actually correct — the two bugs that hit the live deploy could
 * only be caught here, against a running Keycloak:
 * <ol>
 *   <li><b>Realm-role read permission.</b> {@code assignRealmRole} does {@code GET /roles/{name}}
 *       before mapping it. The {@code smartfreight-admin} service account needs {@code view-realm}
 *       on top of {@code manage-users}; without it that GET is 403 and every {@code createUser}
 *       fails. Each create here exercises the role assignment, so a missing grant fails the test.</li>
 *   <li><b>Unmanaged attribute persistence.</b> Keycloak 26's declarative user profile silently
 *       DROPS {@code tenant_id} on Admin-API creation unless the realm sets
 *       {@code unmanagedAttributePolicy}. The {@code tenant_id} round-trip assertions below would
 *       fail (null tenant) if that policy regressed — and a null tenant claim blocks login.</li>
 * </ol>
 *
 * <p>The container is static (shared across methods) so Keycloak boots once; each test uses a
 * distinct email to stay independent. Passwords are policy-compliant
 * ({@code length(10)+upper+lower+digit}) so the realm's {@code passwordPolicy} never trips the
 * provisioning calls regardless of whether the Admin API enforces it.
 */
@Testcontainers
class KeycloakAdminServiceE2ETest {

    @Container
    static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
                    .withRealmImportFile("realm-smartfreight.json");

    private static final String GOOD_PASSWORD = "Operator1Pass";

    private KeycloakAdminService service;

    @BeforeEach
    void setUp() {
        KeycloakAdminProperties props = new KeycloakAdminProperties(
                KEYCLOAK.getAuthServerUrl(), "smartfreight",
                "smartfreight-admin", "smartfreight-admin-secret");
        service = new KeycloakAdminService(props);
    }

    @Test
    void createUser_preservesTenantId_andAssignsRole() {
        String email = "alice@acme.co";

        String userId = service.createUser(email, GOOD_PASSWORD, 42L, "OPERATOR");

        assertThat(userId).isNotBlank();
        // Both prod bugs are guarded here: a 403 on the role read would have thrown above,
        // and a dropped tenant_id would surface as a null tenant now.
        Optional<KeycloakUser> found = service.findUser(userId);
        assertThat(found).isPresent();
        assertThat(found.get().email()).isEqualTo(email);
        assertThat(found.get().tenantId()).isEqualTo(42L);
        assertThat(found.get().role()).isEqualTo("OPERATOR");
    }

    @Test
    void listUsersByTenant_returnsOnlyThatTenant_withDisplayRole() {
        service.createUser("bob@tenant7.co", GOOD_PASSWORD, 7L, "OPERATOR");
        service.createUser("carol@tenant8.co", GOOD_PASSWORD, 8L, "OPERATOR");

        List<KeycloakUser> tenant7 = service.listUsersByTenant(7L);

        assertThat(tenant7).extracting(KeycloakUser::email).contains("bob@tenant7.co");
        assertThat(tenant7).extracting(KeycloakUser::email).doesNotContain("carol@tenant8.co");
        assertThat(tenant7).allSatisfy(u -> {
            assertThat(u.tenantId()).isEqualTo(7L);
            assertThat(u.role()).isEqualTo("OPERATOR");
        });
    }

    @Test
    void seededSuperAdmin_isListedForTenant1_withSuperAdminRoleWinning() {
        List<KeycloakUser> tenant1 = service.listUsersByTenant(1L);

        // operator@smartfreight.com is seeded in the realm import with SUPER_ADMIN on tenant 1.
        KeycloakUser superAdmin = tenant1.stream()
                .filter(u -> "operator@smartfreight.com".equals(u.email()))
                .findFirst()
                .orElseThrow();
        assertThat(superAdmin.tenantId()).isEqualTo(1L);
        assertThat(superAdmin.role()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    void emailExists_trueAfterCreate_falseForUnknown() {
        service.createUser("dave@acme.co", GOOD_PASSWORD, 5L, "OPERATOR");

        assertThat(service.emailExists("dave@acme.co")).isTrue();
        assertThat(service.emailExists("nobody@acme.co")).isFalse();
    }

    @Test
    void resetPassword_succeedsForExistingUser() {
        String userId = service.createUser("erin@acme.co", GOOD_PASSWORD, 9L, "OPERATOR");

        // No exception = Keycloak accepted the credential update.
        service.resetPassword(userId, "FreshPass9Word");
    }

    @Test
    void deleteUser_removesTheUser() {
        String userId = service.createUser("frank@acme.co", GOOD_PASSWORD, 11L, "OPERATOR");
        assertThat(service.findUser(userId)).isPresent();

        service.deleteUser(userId);

        assertThat(service.findUser(userId)).isEmpty();
        assertThat(service.emailExists("frank@acme.co")).isFalse();
    }
}
