package com.smartfreight.inbox.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * The {@code state} of a Microsoft 365 consent link (V38): which SmartFreight tenant issued it.
 *
 * <p>Kept server-side in Redis rather than signed into the link: the value is an unguessable random
 * key, single-use ({@code GETDEL}) and expires. The link is usually forwarded to the customer's IT
 * administrator, who may open it hours later — hence a day, not minutes. Whoever opens it can only
 * bind a directory THEY administer (the directory comes from Microsoft's ID token), so a leaked link
 * cannot hand someone else's mail to anyone.
 */
@Component
public class Ms365ConsentStates {

    static final Duration TTL = Duration.ofHours(24);
    private static final String PREFIX = "smartfreight:ms365-consent:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    public Ms365ConsentStates(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** A fresh state for a link issued by {@code tenantId}. */
    public String issue(long tenantId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(PREFIX + state, Long.toString(tenantId), TTL);
        return state;
    }

    /** The issuing tenant — once; empty when the state is unknown, expired or already used. */
    public Optional<Long> consume(String state) {
        if (state == null || state.isBlank() || state.length() > 100) {
            return Optional.empty();
        }
        String tenant = redis.opsForValue().getAndDelete(PREFIX + state);
        try {
            return Optional.ofNullable(tenant).map(Long::valueOf);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
