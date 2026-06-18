package com.smartfreight.inbox.tenancy;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the {@code tenant_id} claim off a Keycloak bearer JWT (Phase 3.2).
 *
 * <p>Shared by {@link TenantFilter} (the {@code /api/**} request thread) and
 * {@code StompAuthChannelInterceptor} (the WebSocket CONNECT/SUBSCRIBE frames) so both resolve the
 * tenant identically. The realm mapper emits the claim as a JSON number, but we tolerate a string
 * too. A missing or unparseable claim yields {@code null} — callers degrade to the fail-closed
 * anonymous scope rather than trusting the token.
 */
public final class TenantClaims {

    /** Access-token claim carrying the operator's tenant (realm user attribute mapper). */
    public static final String TENANT_CLAIM = "tenant_id";

    private TenantClaims() {
    }

    /** Tenant id from the JWT's {@code tenant_id} claim, or {@code null} when missing/unparseable. */
    public static Long fromJwt(Jwt jwt) {
        return jwt == null ? null : parse(jwt.getClaim(TENANT_CLAIM));
    }

    /** Parses a raw {@code tenant_id} claim value (Number or numeric String) to a tenant id. */
    public static Long parse(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
