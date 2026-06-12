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
 * <p>Principals without a tenant (anonymous actuator scrapes, {@code @WithMockUser} in
 * tests) leave the context unset, which resolves to the default tenant — the documented
 * S1 single-tenant compromise.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TenantUserDetails user) {
            try (TenantContext.Scope ignored = TenantContext.openAs(user.getTenantId())) {
                filterChain.doFilter(request, response);
            }
        } else {
            filterChain.doFilter(request, response);
        }
    }
}
