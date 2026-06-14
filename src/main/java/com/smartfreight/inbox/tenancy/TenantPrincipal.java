package com.smartfreight.inbox.tenancy;

/**
 * A security principal that knows its tenant (Phase 2.2 / 2.3).
 *
 * <p>{@link TenantFilter} reads the tenant off the authenticated principal on every request
 * without touching the DB. Keeping this an interface decouples the filter from the concrete
 * principal type: pre-2.3 it was {@code TenantUserDetails} (form login); since 2.3 it is
 * {@link TenantOidcUser} (Keycloak OIDC). The filter only needs {@link #getTenantId()}.
 */
public interface TenantPrincipal {

    /** The tenant this principal belongs to. Never {@code null} for an authenticated user. */
    Long getTenantId();
}
