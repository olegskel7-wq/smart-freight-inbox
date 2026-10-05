package com.smartfreight.inbox.service;

import com.smartfreight.inbox.config.ResilienceConfig;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the Ollama endpoint for the CURRENT tenant and hands out the matching
 * {@link WebClient} and {@link CircuitBreaker} (Phase 2.2 S3).
 *
 * <p>The endpoint comes through the standard settings override chain
 * ({@code tenant_setting → env default}) under the key {@code ollama.base-url}: a tenant
 * with their own GPU box (or a dedicated model container) points their pipeline at it on
 * {@code /settings}, everyone else inherits the platform instance. AI services call
 * {@link #client()} / {@link #circuitBreaker()} per request — tenant resolution is two
 * indexed reads, noise next to a multi-second LLM call.
 *
 * <p>One {@code WebClient} per distinct URL, cached — and one breaker per distinct URL:
 * a dead tenant-owned Ollama must trip ONLY that tenant's breaker, not park every
 * tenant's tickets. The platform URL keeps the historical breaker name
 * {@code ollama} (Grafana panels/alerts unchanged); overrides get {@code ollama:<url>},
 * auto-bound to Micrometer by the registry's metrics binder.
 *
 * <p>System context (RetryScheduler's open-breaker check, the health indicator) resolves
 * to the platform URL by construction — SettingsService skips tenant overrides there.
 *
 * <p>Gate B7: {@link #recordUsage} meters every completed call per tenant/model/operation
 * ({@code smartfreight_llm_calls_total}, {@code smartfreight_llm_tokens_total}). LLM calls are
 * not stored anywhere else, so these counters are the only source for per-tenant GPU cost and
 * W3 usage metering — unlike the DB analytics, they cannot be rebuilt after the fact.
 */
@Service
public class OllamaClientProvider {

    /** Settings key for the per-tenant override; absent row = platform default. */
    public static final String KEY_OLLAMA_BASE_URL = "ollama.base-url";

    private final SettingsService settingsService;
    private final CircuitBreakerRegistry breakerRegistry;
    private final MeterRegistry meterRegistry;
    private final String platformBaseUrl;

    /** base URL → client. Bounded in practice by the number of distinct tenant endpoints. */
    private final Map<String, WebClient> clients = new ConcurrentHashMap<>();

    public OllamaClientProvider(SettingsService settingsService,
                                CircuitBreakerRegistry breakerRegistry,
                                MeterRegistry meterRegistry,
                                @Value("${ollama.base-url:http://localhost:11434}") String platformBaseUrl) {
        this.settingsService = settingsService;
        this.breakerRegistry = breakerRegistry;
        this.meterRegistry = meterRegistry;
        this.platformBaseUrl = normalize(platformBaseUrl);
    }

    /** WebClient for the current tenant's Ollama endpoint (platform default outside tenant scope). */
    public WebClient client() {
        return clients.computeIfAbsent(currentBaseUrl(), OllamaClientProvider::buildClient);
    }

    /**
     * Circuit breaker scoped to the current tenant's endpoint. Always use the breaker and
     * the client from the same provider call pair — they key off the same resolved URL.
     */
    public CircuitBreaker circuitBreaker() {
        String url = currentBaseUrl();
        String name = url.equals(platformBaseUrl)
                ? ResilienceConfig.OLLAMA_BREAKER
                : ResilienceConfig.OLLAMA_BREAKER + ":" + url;
        // Named instance, "ollama" config: dynamic endpoints inherit the tuned thresholds.
        return breakerRegistry.circuitBreaker(name, ResilienceConfig.OLLAMA_BREAKER);
    }

    /**
     * Meters one completed Ollama call for the CURRENT tenant (Gate B7). Call it right after
     * the request returns — a null response still cost a GPU pass. Token counts come from
     * Ollama's {@code prompt_eval_count}/{@code eval_count} ({@code /api/generate} only;
     * {@code /api/embeddings} reports none). Failed/timed-out calls are not counted.
     */
    public void recordUsage(String operation, String model, Map<String, ?> response) {
        Tags tags = Tags.of("tenant", TenantContext.metricTag(), "model", model, "operation", operation);
        Counter.builder("smartfreight_llm_calls_total")
                .description("Completed Ollama calls per tenant, model and operation")
                .tags(tags)
                .register(meterRegistry)
                .increment();
        if (response == null) {
            return;
        }
        addTokens(tags.and("type", "prompt"), response.get("prompt_eval_count"));
        addTokens(tags.and("type", "completion"), response.get("eval_count"));
    }

    private void addTokens(Tags tags, Object count) {
        if (count instanceof Number n && n.longValue() > 0) {
            Counter.builder("smartfreight_llm_tokens_total")
                    .description("Ollama tokens per tenant, model, operation and type (prompt/completion)")
                    .tags(tags)
                    .register(meterRegistry)
                    .increment(n.doubleValue());
        }
    }

    private String currentBaseUrl() {
        return normalize(settingsService.getString(KEY_OLLAMA_BASE_URL, platformBaseUrl));
    }

    private static String normalize(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * Same timeout/pool profile the old singleton {@code ollamaWebClient} bean had —
     * LLM-sized read timeout, small per-endpoint connection pool.
     */
    private static WebClient buildClient(String baseUrl) {
        ConnectionProvider provider = ConnectionProvider.builder("ollama-pool-" + baseUrl.hashCode())
                .maxConnections(10)
                .maxIdleTime(Duration.ofMinutes(10))
                .maxLifeTime(Duration.ofMinutes(20))
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .build();

        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(150, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
