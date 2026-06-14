package com.smartfreight.inbox.support;

import com.smartfreight.inbox.tenancy.TenantOidcUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Builds a {@code SecurityContext} whose principal is a {@link TenantOidcUser} (Phase 2.3),
 * so the production {@code TenantFilter} binds {@link WithMockTenantOperator#tenantId()} to the
 * request. No Keycloak round-trip — the OIDC principal is synthesised directly, mirroring what
 * {@code TenantOidcUserService} produces from a real token.
 */
public class WithMockTenantOperatorSecurityContextFactory
        implements WithSecurityContextFactory<WithMockTenantOperator> {

    @Override
    public SecurityContext createSecurityContext(WithMockTenantOperator annotation) {
        List<SimpleGrantedAuthority> authorities = Arrays.stream(annotation.roles())
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();

        OidcIdToken idToken = OidcIdToken.withTokenValue("test-id-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .subject(annotation.username())
                .claim("preferred_username", annotation.username())
                .claim("email", annotation.username())
                .claim("tenant_id", annotation.tenantId())
                .build();

        TenantOidcUser principal = new TenantOidcUser(
                authorities, idToken, null, "preferred_username", annotation.tenantId());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(principal, authorities, "keycloak"));
        return context;
    }
}
