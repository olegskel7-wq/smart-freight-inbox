package com.smartfreight.inbox.tenancy;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/**
 * Session principal that carries the user's tenant (Phase 2.2).
 *
 * <p>The tenant is resolved ONCE at login (from {@code app_user.tenant_id}) and rides in
 * the session from then on — {@link TenantFilter} reads it on every request without
 * touching the database. This sidesteps the bootstrap problem: {@code app_user} itself
 * cannot be tenant-filtered, because at authentication time no tenant context exists yet.
 */
public class TenantUserDetails extends User {

    // Sessions are JDK-serialized into Redis (Phase 2.6); pin the version so a redeploy
    // does not invalidate live sessions over an incidental class change. User is Serializable.
    private static final long serialVersionUID = 1L;

    private final Long tenantId;

    public TenantUserDetails(String username, String password,
                             Collection<? extends GrantedAuthority> authorities,
                             Long tenantId) {
        super(username, password, authorities);
        this.tenantId = tenantId;
    }

    public Long getTenantId() {
        return tenantId;
    }
}
