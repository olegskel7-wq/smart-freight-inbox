package com.smartfreight.inbox.tenancy;

import java.util.function.Supplier;

/**
 * Holds the tenant the current thread is working for (Phase 2.2).
 *
 * <p>Three states:
 * <ul>
 *   <li><b>tenant</b> — a concrete tenant id. Set by {@code TenantFilter} for web requests
 *       (from the logged-in user's principal), by the RabbitMQ consumer (from the event
 *       payload) and by ingestion (default tenant until per-tenant mailboxes in S3).</li>
 *   <li><b>system</b> — cross-tenant background work: schedulers, outbox relay, DLQ admin.
 *       Hibernate disables the {@code @TenantId} filter (root tenant) and the RLS policy
 *       admits all rows via the {@code app.is_system} GUC.</li>
 *   <li><b>unset</b> — treated as the default tenant by {@link TenantIdentifierResolver}
 *       and {@link TenantAwareDataSource}. A deliberate S1 compromise while exactly one
 *       real tenant exists; tightens to fail-loud when tenant onboarding arrives (S3).</li>
 * </ul>
 *
 * <p>Always use the {@code runAs*} helpers rather than raw set/clear — they restore the
 * previous state in a finally block, so nested scopes and pooled threads (schedulers,
 * listener containers) can never leak a stale tenant into unrelated work.
 */
public final class TenantContext {

    /** Sentinel for the system (cross-tenant) state — never a valid tenant table id. */
    static final Long SYSTEM = -1L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    // run* takes Runnable, call* takes Supplier — distinct names on purpose: a same-name
    // overload pair is ambiguous for value-returning expression lambdas and method refs.

    /** Runs {@code work} attributed to the given tenant, restoring the previous state after. */
    public static void runAs(Long tenantId, Runnable work) {
        callAs(tenantId, () -> {
            work.run();
            return null;
        });
    }

    /** Like {@link #runAs(Long, Runnable)} but returns the work's result. */
    public static <T> T callAs(Long tenantId, Supplier<T> work) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be a positive id, got: " + tenantId);
        }
        return callWith(tenantId, work);
    }

    /** Runs cross-tenant {@code work} (schedulers, relay), restoring the previous state after. */
    public static void runAsSystem(Runnable work) {
        callAsSystem(() -> {
            work.run();
            return null;
        });
    }

    /** Like {@link #runAsSystem(Runnable)} but returns the work's result. */
    public static <T> T callAsSystem(Supplier<T> work) {
        return callWith(SYSTEM, work);
    }

    /**
     * Opens a tenant scope for try-with-resources — for call sites where the work throws
     * checked exceptions (servlet filters):
     * <pre>{@code try (var ignored = TenantContext.openAs(tenantId)) { chain.doFilter(...); }}</pre>
     */
    public static Scope openAs(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be a positive id, got: " + tenantId);
        }
        return new Scope(tenantId);
    }

    /** Opens a system (cross-tenant) scope for try-with-resources. */
    public static Scope openSystem() {
        return new Scope(SYSTEM);
    }

    /** Restores the previous tenant state on close. */
    public static final class Scope implements AutoCloseable {
        private final Long previous;

        private Scope(Long value) {
            this.previous = CURRENT.get();
            CURRENT.set(value);
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Current tenant id, or {@code null} when unset or in system state. */
    public static Long tenantIdOrNull() {
        Long value = CURRENT.get();
        return SYSTEM.equals(value) ? null : value;
    }

    public static boolean isSystem() {
        return SYSTEM.equals(CURRENT.get());
    }

    private static <T> T callWith(Long value, Supplier<T> work) {
        try (Scope ignored = new Scope(value)) {
            return work.get();
        }
    }
}
