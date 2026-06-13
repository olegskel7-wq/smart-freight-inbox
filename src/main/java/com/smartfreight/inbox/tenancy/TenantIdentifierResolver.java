package com.smartfreight.inbox.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Tells Hibernate which tenant the current session belongs to (Phase 2.2).
 *
 * <p>Drives the discriminator-column multi-tenancy around {@code @TenantId} fields:
 * Hibernate appends {@code tenant_id = ?} to entity queries and assigns the value on
 * insert. This is the first isolation layer; RLS in Postgres (V19) is the second,
 * catching what Hibernate cannot see (native SQL).
 *
 * <p>In the system state ({@link TenantContext#runAsSystem}) the resolver returns the
 * {@link TenantContext#SYSTEM} sentinel, which {@link #isRoot} declares the root tenant —
 * Hibernate then skips {@code @TenantId} filtering entirely (schedulers legitimately see
 * every tenant's rows). The sentinel is never written: system code does not insert
 * {@code @TenantId} entities, and if it ever does, the FK to {@code tenant} fails loudly.
 *
 * <p>The anonymous state maps to the {@link TenantContext#ANONYMOUS} sentinel — NOT root:
 * queries filter to an impossible tenant (zero rows), inserts fail on the FK. Fail-closed
 * for tenantless web traffic (login page, actuator) that happens to touch the ORM.
 *
 * <p>An <b>unset</b> context throws (S3) — but only once the application is fully up.
 * Spring Data opens Hibernate sessions DURING context refresh (repository query
 * validation), on the main thread, with no business context to declare; until
 * {@link ApplicationReadyEvent} those resolve to the fail-closed ANONYMOUS sentinel.
 * From ready onward every entry point must declare its state via {@link TenantContext};
 * the S1 unset-equals-default fallback is gone — a forgotten scheduler or test surfaces
 * here at first DB touch instead of silently reading the default tenant's data.
 *
 * <p>Strictness is an instance flag, not static: cached Spring test contexts coexist in
 * one JVM, and a later context's startup must not be judged by an earlier context's
 * ready event.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<Long> {

    private volatile boolean strict;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        strict = true;
    }

    @Override
    public Long resolveCurrentTenantIdentifier() {
        if (TenantContext.isSystem()) {
            return TenantContext.SYSTEM;
        }
        if (TenantContext.isAnonymous()) {
            return TenantContext.ANONYMOUS;
        }
        Long tenantId = TenantContext.tenantIdOrNull();
        if (tenantId == null) {
            if (strict) {
                throw new IllegalStateException(
                        "Hibernate session opened without a TenantContext. Declare the state "
                                + "explicitly: TenantContext.runAs(tenantId, ...) for tenant work, "
                                + "runAsSystem(...) for cross-tenant work, or openAnonymous() for "
                                + "tenantless requests.");
            }
            // Context refresh still in progress (repository bootstrap) — fail closed, not loud.
            return TenantContext.ANONYMOUS;
        }
        return tenantId;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        // An open Session may move between tenant scopes (e.g. a @Transactional test that
        // exercises several tenants); do not force them to match.
        return false;
    }

    @Override
    public boolean isRoot(Long tenantId) {
        return TenantContext.SYSTEM.equals(tenantId);
    }
}
