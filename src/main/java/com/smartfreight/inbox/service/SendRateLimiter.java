package com.smartfreight.inbox.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-ticket cooldown guard for the "Send reply" action.
 *
 * <p>Purpose: stop a double-click (or a frantic refresh) from sending the same
 * email twice. {@link com.smartfreight.inbox.entity.EmailTicket#getVersion()}
 * already protects against concurrent saves at the JPA level, but it does not
 * protect against two SMTP sends fired in quick succession before either
 * transaction commits.
 *
 * <p>Implementation: an in-memory map from {@code ticketId} to the last accepted
 * send timestamp. Within the cooldown window the second {@code tryAcquire}
 * returns {@code false} and the controller refuses the action.
 *
 * <p>Trade-off — single-instance only:
 * a horizontally scaled deployment would need Redis or a database lock,
 * but the project is currently single-instance (the {@code RetryScheduler}
 * would double-fire across replicas as well).
 *
 * <p>Memory profile: entries are evicted lazily on the next {@code tryAcquire}
 * call once they are past the cooldown — bounded by the number of distinct
 * tickets actioned within the cooldown window.
 */
@Component
public class SendRateLimiter {

    private final Duration cooldown;
    private final ConcurrentMap<Long, Long> lastSendAtMillis = new ConcurrentHashMap<>();

    public SendRateLimiter(@Value("${send.cooldown-seconds:5}") int cooldownSeconds) {
        this.cooldown = Duration.ofSeconds(cooldownSeconds);
    }

    /**
     * Attempts to claim the cooldown slot for a ticket. Atomic via
     * {@link ConcurrentMap#compute(Object, java.util.function.BiFunction)}.
     *
     * @param ticketId target ticket
     * @return {@code true} if the caller may proceed with the send,
     *         {@code false} if another send happened within the cooldown
     */
    public boolean tryAcquire(Long ticketId) {
        long now = System.currentTimeMillis();
        long cooldownMs = cooldown.toMillis();

        Long previous = lastSendAtMillis.compute(ticketId, (id, last) -> {
            if (last == null || now - last >= cooldownMs) {
                return now;
            }
            return last;
        });

        return previous == now;
    }

    /** Returns the cooldown duration — exposed so controllers can render a friendly error message. */
    public Duration getCooldown() {
        return cooldown;
    }
}
