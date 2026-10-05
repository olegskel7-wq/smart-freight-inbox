package com.smartfreight.inbox.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * Circuit breakers for the two external dependencies (Phase 1.7).
 *
 * Resilience4j is used programmatically (no Spring starter — it targets Boot 3) and the
 * breakers are exposed as named beans. Both protect the pipeline from hammering a dead
 * dependency with calls that would each wait out the full WebClient timeout (up to 150 s
 * for Ollama): once the failure rate trips the breaker, calls fail immediately with
 * {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException} until a probe
 * call succeeds in HALF_OPEN state.
 *
 * Semantics on OPEN differ per dependency:
 * — ollama: the pipeline parks the ticket as RETRY_LATER (see TicketProcessingService);
 *   the customer gets a proper AI draft once Ollama recovers instead of an instant
 *   static fallback. RetryScheduler skips re-enqueueing while the breaker is OPEN so
 *   parked tickets do not burn their retry budget against a dead service.
 * — portService: lookup degrades to Optional.empty() exactly like any other lookup
 *   failure today — the draft just reflects missing item data. 4xx responses ("item
 *   not found") are a business miss, not an infrastructure failure, and are ignored
 *   by the breaker.
 *
 * One shared breaker covers all three Ollama call sites (classification, extraction,
 * drafting): it is a single process — if it is down, it is down for everyone.
 *
 * State and call metrics are bound to Micrometer as resilience4j_circuitbreaker_*
 * (Prometheus alert CircuitBreakerOpen + Grafana panel).
 */
@Configuration
public class ResilienceConfig {

    public static final String OLLAMA_BREAKER = "ollama";
    public static final String PORT_SERVICE_BREAKER = "portService";

    /** Sliding window: failure rate is evaluated over the last N calls. */
    private static final int SLIDING_WINDOW_SIZE = 10;

    /** Minimum calls in the window before the failure rate is evaluated at all.
     *  Low on purpose: with consumer concurrency 2 a dead Ollama should trip the
     *  breaker after roughly one ticket's worth of calls, not ten. */
    private static final int MINIMUM_NUMBER_OF_CALLS = 4;

    /** Percentage of failed calls in the window that opens the breaker. */
    private static final float FAILURE_RATE_THRESHOLD = 50f;

    /** Single probe call in HALF_OPEN decides whether the dependency recovered. */
    private static final int PERMITTED_CALLS_IN_HALF_OPEN = 1;

    /** How long the breaker stays OPEN before allowing a probe call. */
    @Value("${resilience.ollama.wait-open-seconds:30}")
    private int ollamaWaitOpenSeconds;

    @Value("${resilience.port-service.wait-open-seconds:30}")
    private int portServiceWaitOpenSeconds;

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meterRegistry) {
        CircuitBreakerConfig ollamaConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(SLIDING_WINDOW_SIZE)
                .minimumNumberOfCalls(MINIMUM_NUMBER_OF_CALLS)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(Duration.ofSeconds(ollamaWaitOpenSeconds))
                .permittedNumberOfCallsInHalfOpenState(PERMITTED_CALLS_IN_HALF_OPEN)
                // Without this the breaker leaves OPEN only on the next call attempt — but
                // RetryScheduler skips its run while getState() is OPEN, so parked tickets waited
                // for the next NEW email to make that attempt (hours, at pilot volume).
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreakerConfig portServiceConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(SLIDING_WINDOW_SIZE)
                .minimumNumberOfCalls(MINIMUM_NUMBER_OF_CALLS)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(Duration.ofSeconds(portServiceWaitOpenSeconds))
                .permittedNumberOfCallsInHalfOpenState(PERMITTED_CALLS_IN_HALF_OPEN)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // "Item not found" (4xx) must not open the breaker — only transport
                // errors, timeouts and 5xx indicate that port-service itself is unwell.
                .ignoreException(t -> t instanceof WebClientResponseException wcre
                        && wcre.getStatusCode().is4xxClientError())
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(Map.of(
                OLLAMA_BREAKER, ollamaConfig,
                PORT_SERVICE_BREAKER, portServiceConfig));

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    public CircuitBreaker ollamaCircuitBreaker(CircuitBreakerRegistry registry) {
        // Second argument = config name: instance "ollama" uses the "ollama" config.
        return registry.circuitBreaker(OLLAMA_BREAKER, OLLAMA_BREAKER);
    }

    @Bean
    public CircuitBreaker portServiceCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(PORT_SERVICE_BREAKER, PORT_SERVICE_BREAKER);
    }
}
