package com.smartfreight.inbox.testutil;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test doubles for transaction plumbing (Phase 2.2).
 *
 * Schedulers now take a {@link TransactionTemplate} (the tenant context must wrap the
 * transaction, so {@code @Transactional} was replaced with programmatic transactions).
 * Unit tests need the template to actually EXECUTE its callback — a Mockito mock would
 * return null and silently skip the body — so this no-op manager begins/commits nothing
 * but runs the work for real.
 */
public final class TestTransactions {

    private TestTransactions() {
    }

    public static TransactionTemplate noOpTemplate() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        });
    }
}
