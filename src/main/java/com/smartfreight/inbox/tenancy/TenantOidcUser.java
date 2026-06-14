package com.smartfreight.inbox.tenancy;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.util.Collection;

/**
 * OIDC session principal that carries the user's tenant (Phase 2.3).
 *
 * <p>The Keycloak token carries the tenant as the {@code tenant_id} claim; the custom
 * {@link com.smartfreight.inbox.config.TenantOidcUserService} reads it once at login and pins
 * it here. {@link TenantFilter} then reads it on every request — the OIDC equivalent of the
 * pre-2.3 {@code TenantUserDetails}.
 */
public class TenantOidcUser extends DefaultOidcUser implements TenantPrincipal {

    // Sessions are JDK-serialized into Redis (Phase 2.6); pin the version so a redeploy does
    // not invalidate live sessions over an incidental class change. DefaultOidcUser is Serializable.
    private static final long serialVersionUID = 1L;

    private final Long tenantId;

    public TenantOidcUser(Collection<? extends GrantedAuthority> authorities,
                          OidcIdToken idToken, OidcUserInfo userInfo,
                          String nameAttributeKey, Long tenantId) {
        super(authorities, idToken, userInfo, nameAttributeKey);
        this.tenantId = tenantId;
    }

    @Override
    public Long getTenantId() {
        return tenantId;
    }
}
