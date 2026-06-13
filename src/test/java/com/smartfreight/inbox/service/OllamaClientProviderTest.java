package com.smartfreight.inbox.service;

import com.smartfreight.inbox.config.ResilienceConfig;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OllamaClientProvider} — per-tenant endpoint resolution, client
 * caching and per-endpoint circuit-breaker naming (Phase 2.2 S3).
 */
class OllamaClientProviderTest {

    private static final String PLATFORM = "http://platform-ollama:11434";

    private SettingsService settings;
    private CircuitBreakerRegistry registry;
    private OllamaClientProvider provider;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        // Mirror ResilienceConfig: a named "ollama" config the provider resolves breakers against.
        registry = CircuitBreakerRegistry.ofDefaults();
        registry.addConfiguration(ResilienceConfig.OLLAMA_BREAKER, CircuitBreakerConfig.ofDefaults());
        provider = new OllamaClientProvider(settings, registry, PLATFORM);
    }

    @Test
    void client_isCachedPerEndpoint() {
        when(settings.getString(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));

        WebClient first = TenantContext.callAs(1L, provider::client);
        WebClient again = TenantContext.callAs(1L, provider::client);

        assertThat(first).isSameAs(again);
    }

    @Test
    void distinctTenantEndpoints_getDistinctClientsAndBreakers() {
        // Tenant 2 overrides the endpoint; tenant 1 inherits the platform default.
        when(settings.getString(anyString(), anyString())).thenAnswer(inv -> {
            Long tenant = TenantContext.tenantIdOrNull();
            return tenant != null && tenant == 2L ? "http://tenant2-gpu:11434" : inv.getArgument(1);
        });

        WebClient platformClient = TenantContext.callAs(1L, provider::client);
        WebClient tenantClient = TenantContext.callAs(2L, provider::client);
        assertThat(platformClient).isNotSameAs(tenantClient);

        // The platform endpoint keeps the historic breaker name (Grafana panels unchanged);
        // an override gets its own, so one tenant's dead Ollama can't open everyone's breaker.
        String platformBreaker = TenantContext.callAs(1L, () -> provider.circuitBreaker().getName());
        String tenantBreaker = TenantContext.callAs(2L, () -> provider.circuitBreaker().getName());
        assertThat(platformBreaker).isEqualTo(ResilienceConfig.OLLAMA_BREAKER);
        assertThat(tenantBreaker).isNotEqualTo(platformBreaker);
    }

    @Test
    void trailingSlashAndPlatformDefault_resolveToSameBreaker() {
        // A blank override resolves to the platform default; a trailing slash must not
        // fork a second breaker/client for what is the same endpoint.
        when(settings.getString(anyString(), anyString())).thenReturn(PLATFORM + "/");

        String name = TenantContext.callAs(1L, () -> provider.circuitBreaker().getName());
        assertThat(name).isEqualTo(ResilienceConfig.OLLAMA_BREAKER);
    }
}
