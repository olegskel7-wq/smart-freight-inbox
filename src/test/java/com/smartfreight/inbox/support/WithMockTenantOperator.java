package com.smartfreight.inbox.support;

import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Like {@code @WithMockUser}, but the principal is a {@code TenantUserDetails} so the
 * production {@code TenantFilter} binds a real tenant to the request (S3 test support).
 *
 * <p>A plain {@code @WithMockUser} principal is not tenant-aware, so since S3 the filter
 * scopes the request as anonymous (fail-closed) — RLS then hides every row and full-stack
 * web tests see an empty database. This annotation makes the request run as a tenant user,
 * matching how real authenticated traffic flows.
 */
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockTenantOperatorSecurityContextFactory.class)
public @interface WithMockTenantOperator {

    /** Tenant the request runs under — defaults to the seed tenant (id 1). */
    long tenantId() default 1L;

    String username() default "operator@smartfreight.com";

    /** Roles WITHOUT the {@code ROLE_} prefix. */
    String[] roles() default {"OPERATOR"};
}
