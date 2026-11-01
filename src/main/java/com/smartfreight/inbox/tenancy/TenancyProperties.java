package com.smartfreight.inbox.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Multi-tenancy settings, bound from the {@code tenant.*} keys.
 *
 * <p>{@code defaultId} names the V18 seed tenant (id 1, "Default") for the two remaining
 * legitimate attributions: outbox events recorded before multi-tenancy (no tenantId in the
 * payload) and the one-time import of the env-configured mailbox into
 * {@code tenant_mailbox} ({@code MailboxBootstrap}). It is NOT a fallback for an unset
 * {@link TenantContext} — that throws.
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
