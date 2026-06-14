package com.smartfreight.inbox.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * <p>Principals without a tenant (the login page, actuator scrapes, static resources) get
 * the fail-closed <b>anonymous</b> scope (S3): tenant entities read as empty, writes fail
 * on the FK. Every request therefore leaves this filter with an explicitly declared state —
 * the unset-equals-default S1 compromise is gone.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        try (TenantContext.Scope ignored = auth != null && auth.getPrincipal() instanceof TenantPrincipal principal
                ? TenantContext.openAs(principal.getTenantId())
                : TenantContext.openAnonymous()) {
            filterChain.doFilter(request, response);
        }
    }
}
