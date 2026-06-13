package com.smartfreight.inbox.tenancy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TenantIdentifierResolver} — the S3 fail-loud contract and the
 * sentinel mapping for system/anonymous states. Same package: reaches the package-private
 * SYSTEM/ANONYMOUS sentinels.
 */
class TenantIdentifierResolverTest {

    private final TenantIdentifierResolver resolver = new TenantIdentifierResolver();

    @Test
    void tenantState_resolvesToThatTenant() {
        TenantContext.runAs(42L, () ->
                assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(42L));
    }

    @Test
    void systemState_resolvesToRootSentinel() {
        TenantContext.runAsSystem(() -> {
            Long id = resolver.resolveCurrentTenantIdentifier();
            assertThat(id).isEqualTo(TenantContext.SYSTEM);
            assertThat(resolver.isRoot(id)).isTrue();
        });
    }

    @Test
    void anonymousState_resolvesToNonRootSentinel() {
        // Anonymous must NOT be root: it filters to an impossible tenant (zero rows),
        // it does not bypass @TenantId like the system state.
        try (var ignored = TenantContext.openAnonymous()) {
            Long id = resolver.resolveCurrentTenantIdentifier();
            assertThat(id).isEqualTo(TenantContext.ANONYMOUS);
            assertThat(resolver.isRoot(id)).isFalse();
        }
    }

    @Test
    void unsetState_beforeReady_failsClosedToAnonymous() {
        // During context refresh (repository bootstrap) there is no business context;
        // resolve to the fail-closed sentinel rather than throwing the app's startup.
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(TenantContext.ANONYMOUS);
    }

    @Test
    void unsetState_afterReady_failsLoud() {
        resolver.onApplicationReady();

        assertThatThrownBy(resolver::resolveCurrentTenantIdentifier)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a TenantContext");
    }
}
