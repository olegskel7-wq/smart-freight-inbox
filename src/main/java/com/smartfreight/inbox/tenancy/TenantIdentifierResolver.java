package com.smartfreight.inbox.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

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
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<Long> {

    private final TenancyProperties properties;

    public TenantIdentifierResolver(TenancyProperties properties) {
        this.properties = properties;
    }

    @Override
    public Long resolveCurrentTenantIdentifier() {
        if (TenantContext.isSystem()) {
            return TenantContext.SYSTEM;
        }
        Long tenantId = TenantContext.tenantIdOrNull();
        return tenantId != null ? tenantId : properties.getDefaultId();
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
