package com.smartfreight.inbox.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Multi-tenancy settings (Phase 2.2), bound from the {@code tenant.*} keys.
 *
 * <p>{@code defaultId} names the V18 seed tenant (id 1, "Default") for the two remaining
 * legitimate attributions: pre-2.2 outbox events still in flight (no tenantId in the
 * payload) and the one-time import of the env-configured mailbox into
 * {@code tenant_mailbox} (S3 bootstrap). It is NOT a fallback for an unset
 * {@link TenantContext} any more — that throws since S3.
 */
@ConfigurationProperties(prefix = "tenant")
public class TenancyProperties {

    /** Tenant attributed when no explicit context exists. Matches the V18 seed row. */
    private long defaultId = 1L;

    public long getDefaultId() {
        return defaultId;
    }

    public void setDefaultId(long defaultId) {
        this.defaultId = defaultId;
    }
}
