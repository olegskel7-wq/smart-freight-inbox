package com.smartfreight.inbox.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the bearer-token role mapping (Phase 3.2): no roles → OPERATOR, and
 * SUPER_ADMIN implies OPERATOR.
 */
class KeycloakJwtRoleConverterTest {

    private final KeycloakJwtRoleConverter converter = new KeycloakJwtRoleConverter();

    private Jwt jwt(Object rolesClaim) {
        Jwt.Builder b = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("op@x.com")
                .claim("tenant_id", 1L);
        if (rolesClaim != null) {
            b.claim("roles", rolesClaim);
        }
        return b.build();
    }

    private Set<String> authorities(Collection<GrantedAuthority> granted) {
        return granted.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void mapsRolesToAuthorities() {
        assertThat(authorities(converter.convert(jwt(List.of("OPERATOR")))))
                .containsExactly("ROLE_OPERATOR");
    }

    @Test
    void superAdminImpliesOperator() {
        assertThat(authorities(converter.convert(jwt(List.of("SUPER_ADMIN")))))
                .contains("ROLE_SUPER_ADMIN", "ROLE_OPERATOR");
    }

    @Test
    void defaultsToOperatorWhenNoRolesClaim() {
        assertThat(authorities(converter.convert(jwt(null))))
                .containsExactly("ROLE_OPERATOR");
    }

    @Test
    void defaultsToOperatorWhenRolesClaimEmpty() {
        assertThat(authorities(converter.convert(jwt(List.of()))))
                .containsExactly("ROLE_OPERATOR");
    }

    @Test
    void ignoresBlankRoleEntries() {
        assertThat(authorities(converter.convert(jwt(List.of("OPERATOR", " ")))))
                .containsExactly("ROLE_OPERATOR");
    }
}
