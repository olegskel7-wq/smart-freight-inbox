package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.CarrierRate;
import com.smartfreight.inbox.repository.CarrierRateRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Redis cache for the rate-sheet lookup (Phase 2.6).
 *
 * <p>{@link RateSheetService#buildRateContext} runs on every drafting pass and starts by
 * fetching all non-expired rates for the day. That query is identical across enquiries within
 * a tenant on a given day, so it is cached here; the per-enquiry route filtering stays in the
 * service (it depends on the email text and would have unbounded cardinality as a cache key).
 *
 * <p>Why a separate bean: Spring's caching proxy only intercepts calls that cross a bean
 * boundary. If {@code validRatesOn} lived on {@link RateSheetService} and were called from its
 * own {@code buildRateContext}, the self-invocation would bypass the proxy and never cache.
 *
 * <p>The cache key is scoped by tenant — {@code findAllValidOn} is RLS-filtered to the current
 * tenant, so a shared Redis must never let one tenant read another's entry. Values are
 * JDK-serialized ({@link CarrierRate} is {@code Serializable}); Boot 4 has no Jackson 2
 * ObjectMapper bean, so a JSON serializer is not an option here.
 */
@Component
public class RateSheetCache {

    /** Cache region name — also configured with a safety-net TTL in {@code CacheConfig}. */
    public static final String CACHE = "rateSheets";

    private final CarrierRateRepository repository;

    public RateSheetCache(CarrierRateRepository repository) {
        this.repository = repository;
    }

    /**
     * All rates quotable on {@code on} for the current tenant, cached per (tenant, date).
     *
     * <p>The date rolls the key over at midnight on its own; within a day the authoritative
     * invalidation is {@link #evictAll()} on CSV import, with the {@code CacheConfig} TTL as a
     * backstop for any path that mutates rates without going through the import.
     */
    @Cacheable(cacheNames = CACHE,
            key = "T(com.smartfreight.inbox.tenancy.TenantContext).tenantIdOrNull() + ':' + #on")
    public List<CarrierRate> validRatesOn(LocalDate on) {
        return repository.findAllValidOn(on);
    }

    /**
     * Drops every cached rate list. Called after a CSV import changes the sheet; clearing the
     * whole region (rather than one tenant's keys) is simpler and safe — imports are a rare,
     * operator-driven action. {@link TenantContext} is irrelevant to a full clear.
     */
    @CacheEvict(cacheNames = CACHE, allEntries = true)
    public void evictAll() {
        // No body — the eviction is the @CacheEvict side effect.
    }
}
