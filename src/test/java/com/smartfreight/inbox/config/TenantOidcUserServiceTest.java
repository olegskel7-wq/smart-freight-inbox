package com.smartfreight.inbox.config;

import com.smartfreight.inbox.tenancy.TenantOidcUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the claims → principal mapping (Phase 2.3). Exercises
 * {@link TenantOidcUserService#toTenantPrincipal} directly so no live Keycloak is needed.
 */
class TenantOidcUserServiceTest {

    private final TenantOidcUserService service = new TenantOidcUserService();

    /** Builds a base OIDC user with the given claims, carrying one OAuth2 scope authority. */
    private OidcUser baseUser(Map<String, Object> claims) {
        OidcIdToken.Builder b = OidcIdToken.withTokenValue("tkn")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        claims.forEach(b::claim);
        OidcIdToken idToken = b.build();
        return new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("SCOPE_openid")), idToken, "preferred_username");
    }

    private Set<String> authorityStrings(TenantOidcUser user) {
        return user.getAuthorities().stream()
                .map(a -> a.getAuthority()).collect(Collectors.toSet());
    }

    @Test
    void mapsTenantIdFromNumericClaim() {
        OidcUser base = baseUser(Map.of(
                "preferred_username", "op@x.com", "tenant_id", 7L, "roles", List.of("OPERATOR")));

        TenantOidcUser principal = service.toTenantPrincipal(base, "preferred_username");

        assertThat(principal.getTenantId()).isEqualTo(7L);
    }

    @Test
    void mapsTenantIdFromStringClaim() {
        OidcUser base = baseUser(Map.of(
                "preferred_username", "op@x.com", "tenant_id", "42", "roles", List.of("OPERATOR")));

        TenantOidcUser principal = service.toTenantPrincipal(base, "preferred_username");

        assertThat(principal.getTenantId()).isEqualTo(42L);
    }

    @Test
    void mapsRolesToAuthoritiesAndKeepsScopes() {
        OidcUser base = baseUser(Map.of(
                "preferred_username", "op@x.com", "tenant_id", 1L, "roles", List.of("OPERATOR")));

        TenantOidcUser principal = service.toTenantPrincipal(base, "preferred_username");

        assertThat(authorityStrings(principal)).contains("ROLE_OPERATOR", "SCOPE_openid");
    }

    @Test
    void superAdminImpliesOperator() {
        OidcUser base = baseUser(Map.of(
                "preferred_username", "boss@x.com", "tenant_id", 1L, "roles", List.of("SUPER_ADMIN")));

        TenantOidcUser principal = service.toTenantPrincipal(base, "preferred_username");

        assertThat(authorityStrings(principal)).contains("ROLE_SUPER_ADMIN", "ROLE_OPERATOR");
    }

    @Test
    void defaultsToOperatorWhenNoRolesClaim() {
        OidcUser base = baseUser(Map.of("preferred_username", "op@x.com", "tenant_id", 1L));

        TenantOidcUser principal = service.toTenantPrincipal(base, "preferred_username");

        assertThat(authorityStrings(principal)).contains("ROLE_OPERATOR");
    }

    @Test
    void rejectsMissingTenantIdClaim() {
        OidcUser base = baseUser(Map.of("preferred_username", "op@x.com", "roles", List.of("OPERATOR")));

        assertThatThrownBy(() -> service.toTenantPrincipal(base, "preferred_username"))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void rejectsUnparseableTenantIdClaim() {
        OidcUser base = baseUser(Map.of(
                "preferred_username", "op@x.com", "tenant_id", "not-a-number"));

        assertThatThrownBy(() -> service.toTenantPrincipal(base, "preferred_username"))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }
}
