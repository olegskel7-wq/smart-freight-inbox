package com.smartfreight.inbox.service;

import com.smartfreight.inbox.config.LoginRateLimitProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/**
 * Redis-backed brute-force throttle for {@code POST /login} (Phase 2.6).
 *
 * <p>Two independent counters per attempt — by client IP and by submitted email — each with a
 * rolling TTL window. Crossing either threshold blocks further attempts (the counter's TTL is
 * extended to the block duration) until it expires. This protects the form-login endpoint, the
 * only externally reachable write surface; there is no public API yet (see ROADMAP 3.5).
 *
 * <p>Counters live in Redis so the limit holds across instances and survives a restart. The
 * check runs before authentication, so a blocked attempt never reaches the password hash or DB.
 */
@Service
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private static final String IP_KEY_PREFIX = "login:fail:ip:";
    private static final String EMAIL_KEY_PREFIX = "login:fail:email:";

    private final StringRedisTemplate redis;
    private final LoginRateLimitProperties props;
    private final Counter blockedByIp;
    private final Counter blockedByEmail;

    public LoginRateLimiter(StringRedisTemplate redis, LoginRateLimitProperties props,
                            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.props = props;
        this.blockedByIp = Counter.builder("smartfreight_login_blocked_total")
                .tag("reason", "ip").register(meterRegistry);
        this.blockedByEmail = Counter.builder("smartfreight_login_blocked_total")
                .tag("reason", "email").register(meterRegistry);
    }

    /**
     * Whether this login attempt should be rejected without authenticating. Counts a metric
     * for the tripped dimension. IP takes precedence in the metric when both are over.
     */
    public boolean isBlocked(String ip, String email) {
        if (!props.isEnabled()) {
            return false;
        }
        if (count(IP_KEY_PREFIX + ip) >= props.getMaxIpFailures()) {
            blockedByIp.increment();
            log.warn("Login blocked: IP {} over failure threshold", ip);
            return true;
        }
        if (email != null && !email.isBlank()
                && count(EMAIL_KEY_PREFIX + normalize(email)) >= props.getMaxEmailFailures()) {
            blockedByEmail.increment();
            log.warn("Login blocked: account {} over failure threshold", email);
            return true;
        }
        return false;
    }

    /** Records a failed attempt against both the IP and the email counter. */
    public void recordFailure(String ip, String email) {
        if (!props.isEnabled()) {
            return;
        }
        bump(IP_KEY_PREFIX + ip, props.getMaxIpFailures());
        if (email != null && !email.isBlank()) {
            bump(EMAIL_KEY_PREFIX + normalize(email), props.getMaxEmailFailures());
        }
    }

    /** Clears the email counter after a successful login; the IP counter is left to expire. */
    public void reset(String email) {
        if (email != null && !email.isBlank()) {
            redis.delete(EMAIL_KEY_PREFIX + normalize(email));
        }
    }

    private long count(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0L : Long.parseLong(value);
    }

    /**
     * Increments a counter, setting the window TTL on first failure and extending it to the
     * block duration once the threshold is reached (so a tripped key stays blocked for the
     * full block window rather than expiring at the end of the original counting window).
     */
    private void bump(String key, int threshold) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            return;
        }
        if (count == 1L) {
            redis.expire(key, Duration.ofSeconds(props.getWindowSeconds()));
        }
        if (count >= threshold) {
            redis.expire(key, Duration.ofSeconds(props.getBlockSeconds()));
        }
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
