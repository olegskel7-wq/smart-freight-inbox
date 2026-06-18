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
 * <p>A user with no roles defaults to {@code OPERATOR}, and {@code SUPER_ADMIN} implies
 * {@code OPERATOR} (granted alongside, not via a role-hierarchy bean) — the authority rules the
 * removed OIDC-login path used to apply. The realm role mapper emits {@code roles} into the
 * access token (see {@code keycloak/realm-smartfreight.json}).
 */
public class KeycloakJwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /** Default role when a token carries none. */
    private static final String DEFAULT_ROLE = "OPERATOR";
    /** Platform admin — implies OPERATOR. */
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
        if (roles.contains(SUPER_ADMIN_ROLE)) {
            roles.add(DEFAULT_ROLE);
        }
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return List.copyOf(authorities);
    }
}
