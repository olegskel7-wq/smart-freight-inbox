package com.smartfreight.inbox.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps a Keycloak access-token's {@code roles} claim to Spring {@code ROLE_*} authorities
 * (Phase 3.2 — the {@code /api/**} resource-server chain).
 *
 * <p>A user with no roles defaults to {@code OPERATOR}; the tenant tiers nest as
 * {@code SUPER_ADMIN} ⇒ {@code ADMIN} ⇒ {@code OPERATOR} (granted alongside, not via a
 * role-hierarchy bean) — so a platform admin can reach every {@code /api} surface and a tenant
 * admin reaches the operator surface plus the management endpoints. The realm role mapper emits
 * {@code roles} into the access token (see {@code keycloak/realm-smartfreight.json}).
 */
public class KeycloakJwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /** Default role when a token carries none. */
    private static final String DEFAULT_ROLE = "OPERATOR";
    /** Tenant admin — manages this tenant's operators/settings; implies OPERATOR. */
    private static final String ADMIN_ROLE = "ADMIN";
    /** Platform admin — manages tenants; implies ADMIN (and thus OPERATOR). */
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";

    private static final String ROLES_CLAIM = "roles";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();
        Object raw = jwt.getClaim(ROLES_CLAIM);
        if (raw instanceof Collection<?> c) {
            for (Object r : c) {
                if (r != null && !r.toString().isBlank()) {
                    roles.add(r.toString());
                }
            }
        }
        if (roles.isEmpty()) {
            roles.add(DEFAULT_ROLE);
        }
        // Implication chain: SUPER_ADMIN ⇒ ADMIN ⇒ OPERATOR.
        if (roles.contains(SUPER_ADMIN_ROLE)) {
            roles.add(ADMIN_ROLE);
        }
        if (roles.contains(ADMIN_ROLE)) {
            roles.add(DEFAULT_ROLE);
        }
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return List.copyOf(authorities);
    }
}
