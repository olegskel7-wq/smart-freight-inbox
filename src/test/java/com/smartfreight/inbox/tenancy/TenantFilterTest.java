package com.smartfreight.inbox.tenancy;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests how {@link TenantFilter} resolves the tenant for each principal shape (Phase 3.2): a
 * {@link TenantPrincipal} (OIDC login) and a {@code Jwt} (resource server), plus the fail-closed
 * anonymous fallbacks. Captures the {@link TenantContext} state seen by the wrapped chain.
 */
class TenantFilterTest {

    private final TenantFilter filter = new TenantFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Jwt jwtWithTenant(Object tenantClaim) {
        Jwt.Builder b = Jwt.withTokenValue("token").header("alg", "none").subject("op@x.com");
        if (tenantClaim != null) {
            b.claim("tenant_id", tenantClaim);
        }
        return b.build();
    }

    /** Runs the filter and returns the tenant id observed inside the chain. */
    private Long tenantSeenByChain() throws Exception {
        AtomicReference<Long> seen = new AtomicReference<>();
        AtomicBoolean anonymous = new AtomicBoolean();
        FilterChain chain = (req, res) -> {
            seen.set(TenantContext.tenantIdOrNull());
            anonymous.set(TenantContext.isAnonymous());
        };
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        lastAnonymous = anonymous.get();
        return seen.get();
    }

    private boolean lastAnonymous;

    @Test
    void readsTenantFromJwtNumericClaim() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtWithTenant(7L)));

        assertThat(tenantSeenByChain()).isEqualTo(7L);
    }

    @Test
    void readsTenantFromJwtStringClaim() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtWithTenant("42")));

        assertThat(tenantSeenByChain()).isEqualTo(42L);
    }

    @Test
    void jwtWithoutTenantClaimFallsBackToAnonymous() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtWithTenant(null)));

        assertThat(tenantSeenByChain()).isNull();
        assertThat(lastAnonymous).isTrue();
    }

    @Test
    void readsTenantFromTenantPrincipal() throws Exception {
        TenantPrincipal principal = () -> 5L;
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(principal, "creds"));

        assertThat(tenantSeenByChain()).isEqualTo(5L);
    }

    @Test
    void noAuthenticationIsAnonymous() throws Exception {
        assertThat(tenantSeenByChain()).isNull();
        assertThat(lastAnonymous).isTrue();
    }

    @Test
    void contextIsRestoredAfterRequest() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtWithTenant(7L)));

        tenantSeenByChain();

        // The scope is closed in a finally — nothing leaks onto the thread.
        assertThat(TenantContext.isUnset()).isTrue();
    }
}
