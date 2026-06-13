package com.smartfreight.inbox.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Login brute-force throttling config (Phase 2.6), bound from the {@code ratelimit.login.*}
 * block. Platform-level, not a per-tenant feature flag: login runs before any tenant context
 * exists, so the per-tenant settings chain is unavailable here.
 */
@Component
@ConfigurationProperties(prefix = "ratelimit.login")
@Getter
@Setter
public class LoginRateLimitProperties {

    /** Master switch; when false the filter passes every login through untouched. */
    private boolean enabled = true;

    /** Failed attempts from one IP within the window before that IP is blocked. */
    private int maxIpFailures = 10;

    /** Failed attempts against one email within the window before that account is blocked. */
    private int maxEmailFailures = 5;

    /** Rolling window over which failures are counted, seconds. */
    private long windowSeconds = 900;

    /** How long a tripped IP/email stays blocked, seconds (counter TTL is refreshed on block). */
    private long blockSeconds = 900;
}
