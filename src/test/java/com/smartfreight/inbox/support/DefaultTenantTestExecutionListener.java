package com.smartfreight.inbox.support;

import com.smartfreight.inbox.tenancy.TenantContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Binds the default tenant to the test thread for the whole test method (S3 test support).
 *
 * <p>Since Phase 2.2 S3 an unset {@link TenantContext} fails loud once the context is
 * ready — and {@code @Transactional} integration tests open the Hibernate session at
 * transaction begin, which happens BEFORE {@code @BeforeEach}. So a scope set inside the
 * test body is too late; this listener opens it earlier.
 *
 * <p>Ordered at 3900, just before Spring's {@code TransactionalTestExecutionListener}
 * (4000): {@code beforeTestMethod} runs ascending (tenant set, then TX begins under it),
 * {@code afterTestMethod} runs descending (TX rolls back, then the scope closes).
 *
 * <p>The bound tenant is the default (id 1); tests that need another tenant or the system
 * context open their own nested scopes, which restore this base on close.
 */
public class DefaultTenantTestExecutionListener extends AbstractTestExecutionListener {

    private static final String SCOPE_ATTRIBUTE = "defaultTenantScope";

    @Override
    public int getOrder() {
        return 3900;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        testContext.setAttribute(SCOPE_ATTRIBUTE, TenantContext.openAs(1L));
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        if (testContext.getAttribute(SCOPE_ATTRIBUTE) instanceof TenantContext.Scope scope) {
            scope.close();
        }
    }
}
