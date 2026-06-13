package com.smartfreight.inbox.tenancy;

import java.util.function.Supplier;

/**
 * Holds the tenant the current thread is working for (Phase 2.2).
 *
 * <p>Four states:
 * <ul>
 *   <li><b>tenant</b> — a concrete tenant id. Set by {@code TenantFilter} for web requests
 *       (from the logged-in user's principal), by the RabbitMQ consumer (from the event
 *       payload) and by the per-mailbox IMAP adapters (from the owning mailbox row, S3).</li>
 *   <li><b>system</b> — cross-tenant background work: schedulers, outbox relay, DLQ admin,
 *       login lookups. Hibernate disables the {@code @TenantId} filter (root tenant) and
 *       the RLS policy admits all rows via the {@code app.is_system} GUC.</li>
 *   <li><b>anonymous</b> — an authenticated-but-tenantless or unauthenticated web request
 *       (login page, static resources, actuator scrapes). Fail-closed: Hibernate filters
 *       tenant entities to an impossible tenant, RLS sees an empty GUC. Reads of tenant
 *       data return nothing, writes fail on the FK.</li>
 *   <li><b>unset</b> — a bug since S3. {@link TenantIdentifierResolver} throws: every
 *       entry point (web filter, consumer, adapters, schedulers, tests) must declare
 *       its state explicitly. The S1 unset-equals-default compromise is gone.</li>
 * </ul>
 *
 * <p>Always use the {@code runAs*} helpers rather than raw set/clear — they restore the
 * previous state in a finally block, so nested scopes and pooled threads (schedulers,
 * listener containers) can never leak a stale tenant into unrelated work.
 */
public final class TenantContext {

    /** Sentinel for the system (cross-tenant) state — never a valid tenant table id. */
    static final Long SYSTEM = -1L;

    /**
     * Sentinel for the anonymous (no-tenant) state — never a valid tenant table id and
     * never declared root: Hibernate filters {@code @TenantId} queries to this impossible
     * tenant (zero rows) and stamps it on inserts (loud FK failure).
     */
    static final Long ANONYMOUS = -2L;

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

    /** Opens a fail-closed scope for requests that carry no tenant (anonymous web traffic). */
    public static Scope openAnonymous() {
        return new Scope(ANONYMOUS);
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

    /** Current tenant id, or {@code null} when in any non-tenant state (system/anonymous/unset). */
    public static Long tenantIdOrNull() {
        Long value = CURRENT.get();
        return SYSTEM.equals(value) || ANONYMOUS.equals(value) ? null : value;
    }

    /**
     * Current tenant id, failing loudly when the thread is not bound to a concrete tenant.
     * For call sites that are only reachable from tenant-scoped work (authenticated
     * controllers) and have no meaningful anonymous/system behaviour.
     */
    public static Long requireTenantId() {
        Long tenantId = tenantIdOrNull();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "A concrete tenant is required here, but the context is " + describeState());
        }
        return tenantId;
    }

    public static boolean isSystem() {
        return SYSTEM.equals(CURRENT.get());
    }

    public static boolean isAnonymous() {
        return ANONYMOUS.equals(CURRENT.get());
    }

    /** True when the thread never declared a state — a bug under S3 fail-loud semantics. */
    public static boolean isUnset() {
        return CURRENT.get() == null;
    }

    static String describeState() {
        Long value = CURRENT.get();
        if (value == null) {
            return "unset";
        }
        if (SYSTEM.equals(value)) {
            return "system";
        }
        if (ANONYMOUS.equals(value)) {
            return "anonymous";
        }
        return "tenant " + value;
    }

    private static <T> T callWith(Long value, Supplier<T> work) {
        try (Scope ignored = new Scope(value)) {
            return work.get();
        }
    }
}
