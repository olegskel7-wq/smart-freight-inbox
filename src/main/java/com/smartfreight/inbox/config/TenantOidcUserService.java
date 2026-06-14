package com.smartfreight.inbox.config;

import com.smartfreight.inbox.tenancy.TenantOidcUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the application principal from the Keycloak OIDC token (Phase 2.3).
 *
 * <p>Keycloak is the sole identity provider: {@code tenant_id} (a user attribute) and the
 * realm roles ride in the token as the {@code tenant_id} and {@code roles} claims (see the
 * protocol mappers in {@code keycloak/realm-smartfreight.json}). This service reads them once
 * at login and produces a {@link TenantOidcUser}, so the rest of the app keeps using
 * {@code hasRole(...)} and {@link com.smartfreight.inbox.tenancy.TenantFilter} keeps reading
 * the tenant off the principal — exactly as under the pre-2.3 form login.
 *
 * <p>Runs inside the security filter chain, before {@code TenantFilter}, and touches no DB —
 * so it raises no {@code TenantContext} concern. A missing/blank {@code tenant_id} claim is
 * rejected (fail-closed): a tenantless principal must never reach the app.
 */
@Service
public class TenantOidcUserService extends OidcUserService {

    private static final Logger log = LoggerFactory.getLogger(TenantOidcUserService.class);

    /** Default role when a user carries none — mirrors the pre-2.3 DbUserDetailsService default. */
    private static final String DEFAULT_ROLE = "OPERATOR";
    /** Platform admin — implies OPERATOR (granted alongside, not via a role hierarchy bean). */
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";

    private static final String TENANT_CLAIM = "tenant_id";
    private static final String ROLES_CLAIM = "roles";

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidc = super.loadUser(userRequest);
        String nameKey = userRequest.getClientRegistration().getProviderDetails()
                .getUserInfoEndpoint().getUserNameAttributeName();
        return toTenantPrincipal(oidc, nameKey);
    }

    /**
     * Turns the base OIDC user into a {@link TenantOidcUser} (claims → tenant + roles). Split out
     * from {@link #loadUser} so the mapping can be unit-tested without a live userinfo endpoint.
     */
    TenantOidcUser toTenantPrincipal(OidcUser oidc, String nameAttributeKey) {
        Long tenantId = parseTenantId(oidc);
        Collection<GrantedAuthority> authorities = mapAuthorities(oidc);
        String nameKey = (nameAttributeKey == null || nameAttributeKey.isBlank())
                ? "preferred_username" : nameAttributeKey;
        return new TenantOidcUser(authorities, oidc.getIdToken(), oidc.getUserInfo(), nameKey, tenantId);
    }

    /**
     * Reads the {@code tenant_id} claim. The realm mapper emits it as a JSON number
     * ({@code jsonType.label=long}), but the userinfo endpoint may serialise it as a string —
     * tolerate both. Absent/blank/non-numeric → fail-closed.
     */
    private Long parseTenantId(OidcUser oidc) {
        Object raw = oidc.getClaims().get(TENANT_CLAIM);
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to the fail-closed branch
            }
        }
        log.warn("OIDC login rejected: missing or unparseable tenant_id claim for subject {}",
                oidc.getSubject());
        throw new OAuth2AuthenticationException(
                new OAuth2Error("invalid_tenant", "tenant_id claim is missing or invalid", null));
    }

    /**
     * Maps the realm-roles claim to {@code ROLE_*} authorities, keeping the OAuth2 scope
     * authorities the base service already granted. SUPER_ADMIN implies OPERATOR (same rule
     * as the pre-2.3 DbUserDetailsService). A user with no roles falls back to OPERATOR.
     */
    private Collection<GrantedAuthority> mapAuthorities(OidcUser oidc) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(oidc.getAuthorities());

        Object raw = oidc.getClaims().get(ROLES_CLAIM);
        Set<String> roles = new LinkedHashSet<>();
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
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return List.copyOf(authorities);
    }
}
