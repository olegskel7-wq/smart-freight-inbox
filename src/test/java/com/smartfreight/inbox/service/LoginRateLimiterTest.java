package com.smartfreight.inbox.service;

import com.smartfreight.inbox.config.LoginRateLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LoginRateLimiter}: threshold checks per IP and email, counter
 * increment with window/block TTLs, and reset on success. Redis is mocked.
 */
@ExtendWith(MockitoExtension.class)
class LoginRateLimiterTest {

    private static final String IP = "203.0.113.7";
    private static final String EMAIL = "Operator@smartfreight.com";
    private static final String IP_KEY = "login:fail:ip:" + IP;
    private static final String EMAIL_KEY = "login:fail:email:operator@smartfreight.com";

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private LoginRateLimiter limiter;
    private LoginRateLimitProperties props;

    @BeforeEach
    void setUp() {
        props = new LoginRateLimitProperties(); // defaults: ip=10, email=5, window/block=900
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        limiter = new LoginRateLimiter(redis, props, new SimpleMeterRegistry());
    }

    @Test
    void isBlocked_underBothThresholds_false() {
        when(valueOps.get(IP_KEY)).thenReturn("3");
        when(valueOps.get(EMAIL_KEY)).thenReturn("1");

        assertThat(limiter.isBlocked(IP, EMAIL)).isFalse();
    }

    @Test
    void isBlocked_ipOverThreshold_true() {
        when(valueOps.get(IP_KEY)).thenReturn("10");

        assertThat(limiter.isBlocked(IP, EMAIL)).isTrue();
        // email counter not consulted once the IP already trips
        verify(valueOps, never()).get(EMAIL_KEY);
    }

    @Test
    void isBlocked_emailOverThreshold_true() {
        when(valueOps.get(IP_KEY)).thenReturn(null);
        when(valueOps.get(EMAIL_KEY)).thenReturn("5");

        assertThat(limiter.isBlocked(IP, EMAIL)).isTrue();
    }

    @Test
    void isBlocked_disabled_false() {
        props.setEnabled(false);

        assertThat(limiter.isBlocked(IP, EMAIL)).isFalse();
        verify(valueOps, never()).get(IP_KEY);
    }

    @Test
    void recordFailure_firstFailure_setsWindowTtl() {
        when(valueOps.increment(IP_KEY)).thenReturn(1L);
        when(valueOps.increment(EMAIL_KEY)).thenReturn(1L);

        limiter.recordFailure(IP, EMAIL);

        verify(redis).expire(IP_KEY, Duration.ofSeconds(900));
        verify(redis).expire(EMAIL_KEY, Duration.ofSeconds(900));
    }

    @Test
    void recordFailure_reachesThreshold_extendsToBlockTtl() {
        when(valueOps.increment(IP_KEY)).thenReturn(10L);
        when(valueOps.increment(EMAIL_KEY)).thenReturn(2L);

        limiter.recordFailure(IP, EMAIL);

        // IP hit the threshold → TTL extended to the block window
        verify(redis).expire(eq(IP_KEY), eq(Duration.ofSeconds(900)));
        // email still mid-window (count 2, not first, below threshold) → no expire call
        verify(redis, never()).expire(eq(EMAIL_KEY), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reset_deletesEmailCounterOnly() {
        limiter.reset(EMAIL);

        verify(redis).delete(EMAIL_KEY);
        verify(redis, never()).delete(IP_KEY);
    }
}
