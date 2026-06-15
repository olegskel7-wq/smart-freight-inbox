package com.smartfreight.inbox.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds the logged-in user's tenant to the request thread (Phase 2.2).
 *
 * <p>Registered as a plain servlet filter bean: Boot orders it after the Spring Security
 * chain (order -100), so the authentication is already populated. Everything downstream —
 * controllers, services, Hibernate sessions, RLS GUCs — sees the tenant from the principal.
 *
 * <p>Two principal shapes are supported (Phase 3.2): a {@link TenantPrincipal} (the Thymeleaf
 * OIDC-login chain, tenant parsed once at login) and a {@code Jwt} (the {@code /api/**}
 * resource-server chain, tenant read from the {@code tenant_id} claim per request).
 *
 * <p>Principals without a tenant (the login page, actuator scrapes, static resources, or a
 * token missing/with an unparseable {@code tenant_id}) get the fail-closed <b>anonymous</b>
 * scope (S3): tenant entities read as empty, writes fail on the FK. Every request therefore
 * leaves this filter with an explicitly declared state — the unset-equals-default S1
 * compromise is gone.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

    private static final String TENANT_CLAIM = "tenant_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long tenantId = resolveTenantId(auth);
        try (TenantContext.Scope ignored = tenantId != null
                ? TenantContext.openAs(tenantId)
                : TenantContext.openAnonymous()) {
            filterChain.doFilter(request, response);
        }
    }

    /** Returns the tenant for the current authentication, or {@code null} (→ anonymous scope). */
    private Long resolveTenantId(Authentication auth) {
        if (auth == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof TenantPrincipal tenantPrincipal) {
            return tenantPrincipal.getTenantId();
        }
        if (principal instanceof Jwt jwt) {
            return parseTenantClaim(jwt.getClaim(TENANT_CLAIM), jwt.getSubject());
        }
        return null;
    }

    /**
     * Reads the {@code tenant_id} claim from a bearer JWT. The realm mapper emits it as a JSON
     * number; tolerate a string too (mirrors {@code TenantOidcUserService.parseTenantId}). Unlike
     * login — which rejects a tenantless principal outright — here we degrade to the fail-closed
     * anonymous scope (RLS returns zero rows), so a misconfigured token can never read another
     * tenant's data.
     */
    private Long parseTenantClaim(Object raw, String subject) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to the warn + anonymous branch
            }
        }
        log.warn("Bearer token missing or with unparseable tenant_id claim (subject {}); "
                + "request runs in anonymous scope", subject);
        return null;
    }
}
