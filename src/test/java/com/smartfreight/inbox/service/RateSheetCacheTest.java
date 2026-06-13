package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.CarrierRate;
import com.smartfreight.inbox.repository.CarrierRateRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the Spring caching behaviour of {@link RateSheetCache} with a real caching proxy
 * (ConcurrentMapCacheManager, so no Redis is needed): the per-(tenant, date) lookup is served
 * from cache on the second call, {@code evictAll} forces a reload, and different tenants are
 * cached under separate keys.
 *
 * <p>The Spring context (hence the cache manager and the mocked repository) is shared across
 * test methods, so {@link #reset()} clears both between tests to keep them independent.
 */
@SpringJUnitConfig
class RateSheetCacheTest {

    // proxyTargetClass=true mirrors Spring Boot's production default (CGLIB) so this minimal
    // context proxies the concrete, interface-less RateSheetCache exactly as the real app does.
    @Configuration
    @EnableCaching(proxyTargetClass = true)
    static class Config {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(RateSheetCache.CACHE);
        }

        @Bean
        CarrierRateRepository repository() {
            return Mockito.mock(CarrierRateRepository.class);
        }

        @Bean
        RateSheetCache rateSheetCache(CarrierRateRepository repository) {
            return new RateSheetCache(repository);
        }
    }

    @Autowired private RateSheetCache cache;
    @Autowired private CarrierRateRepository repository;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void reset() {
        Mockito.reset(repository);
        cacheManager.getCache(RateSheetCache.CACHE).clear();
        when(repository.findAllValidOn(any(LocalDate.class))).thenReturn(List.of(new CarrierRate()));
    }

    @Test
    void validRatesOn_secondCallServedFromCache() {
        LocalDate today = LocalDate.now();

        try (var ignored = TenantContext.openAs(1L)) {
            cache.validRatesOn(today);
            cache.validRatesOn(today);
        }

        verify(repository, times(1)).findAllValidOn(today);
    }

    @Test
    void evictAll_forcesReloadFromRepository() {
        LocalDate today = LocalDate.now();

        try (var ignored = TenantContext.openAs(1L)) {
            cache.validRatesOn(today);
            cache.evictAll();
            cache.validRatesOn(today);
        }

        verify(repository, times(2)).findAllValidOn(today);
    }

    @Test
    void validRatesOn_differentTenantsCachedSeparately() {
        LocalDate today = LocalDate.now();

        try (var ignored = TenantContext.openAs(1L)) {
            cache.validRatesOn(today);
        }
        try (var ignored = TenantContext.openAs(2L)) {
            cache.validRatesOn(today);
        }

        // Per-tenant key → tenant 2 is a cache miss, so the repo is hit once per tenant
        verify(repository, times(2)).findAllValidOn(today);
    }
}
