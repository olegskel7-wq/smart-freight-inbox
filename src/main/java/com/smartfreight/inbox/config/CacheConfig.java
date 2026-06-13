package com.smartfreight.inbox.config;

import com.smartfreight.inbox.service.RateSheetCache;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;

/**
 * Spring Cache wiring (Phase 2.6). Backed by Redis ({@code spring.cache.type=redis}); the
 * region is keyed per tenant in {@link RateSheetCache}.
 *
 * <p>The TTL is a safety net, not the primary invalidation. CSV imports evict the region
 * explicitly, but the TTL bounds staleness for any other path that changes rates (e.g. a
 * direct DB edit) so the cache self-heals within a few minutes without a restart.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Upper bound on how long a stale rate list can survive an out-of-band rate change. */
    private static final Duration RATE_SHEET_TTL = Duration.ofMinutes(10);

    @Bean
    public RedisCacheManagerBuilderCustomizer rateSheetCacheCustomizer() {
        return builder -> builder.withCacheConfiguration(
                RateSheetCache.CACHE,
                RedisCacheConfiguration.defaultCacheConfig().entryTtl(RATE_SHEET_TTL));
    }
}
