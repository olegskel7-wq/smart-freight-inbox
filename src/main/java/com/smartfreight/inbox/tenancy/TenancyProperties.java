package com.smartfreight.inbox.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Multi-tenancy settings (Phase 2.2), bound from the {@code tenant.*} keys.
 *
 * <p>{@code defaultId} is the tenant that owns everything not explicitly attributed:
 * the single IMAP mailbox (until per-tenant mailboxes in S3), pre-2.2 outbox events
 * still in flight, and threads that never set a {@link TenantContext}. Seeded as
 * tenant id 1 ("Default") by migration V18.
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
