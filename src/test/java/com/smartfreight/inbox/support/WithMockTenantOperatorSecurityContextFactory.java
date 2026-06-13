package com.smartfreight.inbox.support;

import com.smartfreight.inbox.tenancy.TenantUserDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Builds a {@code SecurityContext} whose principal is a {@link TenantUserDetails}, so the
 * production {@code TenantFilter} binds {@link WithMockTenantOperator#tenantId()} to the
 * request (S3 test support). No DB lookup — the principal is synthesised directly.
 */
public class WithMockTenantOperatorSecurityContextFactory
        implements WithSecurityContextFactory<WithMockTenantOperator> {

    @Override
    public SecurityContext createSecurityContext(WithMockTenantOperator annotation) {
        List<SimpleGrantedAuthority> authorities = Arrays.stream(annotation.roles())
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();

        TenantUserDetails principal = new TenantUserDetails(
                annotation.username(), "n/a", authorities, annotation.tenantId());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, principal.getPassword(), principal.getAuthorities()));
        return context;
    }
}
