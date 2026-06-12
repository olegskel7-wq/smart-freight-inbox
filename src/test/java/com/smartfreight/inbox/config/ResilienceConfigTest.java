package com.smartfreight.inbox.config;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the circuit breaker configuration (Phase 1.7).
 *
 * Pins down the two behaviours the rest of the system relies on:
 * 1. The portService breaker ignores 4xx ("item not found" is a business miss) but
 *    opens on a burst of transport-level failures.
 * 2. The ollama breaker opens after MINIMUM_NUMBER_OF_CALLS consecutive failures and
 *    then rejects calls with CallNotPermittedException (which the pipeline turns into
 *    RETRY_LATER parking).
 */
class ResilienceConfigTest {

    private CircuitBreaker ollama;
    private CircuitBreaker portService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        ResilienceConfig config = new ResilienceConfig();
        ReflectionTestUtils.setField(config, "ollamaWaitOpenSeconds", 30);
        ReflectionTestUtils.setField(config, "portServiceWaitOpenSeconds", 30);
        meterRegistry = new SimpleMeterRegistry();
        CircuitBreakerRegistry registry = config.circuitBreakerRegistry(meterRegistry);
        ollama = config.ollamaCircuitBreaker(registry);
        portService = config.portServiceCircuitBreaker(registry);
    }

    private static void failCall(CircuitBreaker breaker, RuntimeException failure) {
        try {
            breaker.executeSupplier(() -> { throw failure; });
        } catch (RuntimeException expected) {
            // recorded by the breaker — that is the point
        }
    }

    private static WebClientResponseException notFound() {
        return WebClientResponseException.create(404, "Not Found", HttpHeaders.EMPTY, new byte[0], null);
    }

    @Test
    void portServiceBreaker_ignores4xx_staysClosed() {
        // A whole sliding window of "item not found" responses must not open the breaker.
        for (int i = 0; i < 10; i++) {
            failCall(portService, notFound());
        }

        assertThat(portService.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(portService.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void portServiceBreaker_opensOnTransportFailures() {
        for (int i = 0; i < 4; i++) {
            failCall(portService, new RuntimeException("Connection refused"));
        }

        assertThat(portService.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void ollamaBreaker_opensAfterMinimumFailures_thenRejectsCalls() {
        for (int i = 0; i < 4; i++) {
            failCall(ollama, new RuntimeException("Ollama down"));
        }

        assertThat(ollama.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> ollama.executeSupplier(() -> "never reached"))
                .isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void ollamaBreaker_successfulCalls_keepItClosed() {
        for (int i = 0; i < 10; i++) {
            ollama.executeSupplier(() -> "ok");
        }

        assertThat(ollama.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void breakerStateMetrics_areBoundToMicrometer() {
        // The Grafana panel and the CircuitBreakerOpen alert depend on this gauge family.
        assertThat(meterRegistry.find("resilience4j.circuitbreaker.state").gauges()).isNotEmpty();
    }
}
