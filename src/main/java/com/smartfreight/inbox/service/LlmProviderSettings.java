package com.smartfreight.inbox.service;

import com.smartfreight.inbox.tenancy.TenantContext;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * Which LLM a tenant runs on: {@code local} (its Ollama endpoint — the default) or {@code cloud} (Claude
 * over the Anthropic API). Stored as the per-tenant setting {@code llm.provider}; only the platform
 * operator switches it (SUPER_ADMIN on /tenants), because a cloud tenant's mail leaves our servers and
 * that needs Anthropic named as a subprocessor in the tenant's DPA first.
 */
@Service
public class LlmProviderSettings {

    public static final String KEY_PROVIDER = "llm.provider";
    public static final String LOCAL = "local";
    public static final String CLOUD = "cloud";
    private static final Set<String> PROVIDERS = Set.of(LOCAL, CLOUD);

    private final SettingsService settingsService;
    private final CloudLlmClient cloudLlmClient;

    public LlmProviderSettings(SettingsService settingsService, CloudLlmClient cloudLlmClient) {
        this.settingsService = settingsService;
        this.cloudLlmClient = cloudLlmClient;
    }

    /** Whether the CURRENT tenant runs on the cloud LLM (system context: the platform default, local). */
    public boolean isCloud() {
        return CLOUD.equals(normalise(settingsService.getString(KEY_PROVIDER, LOCAL)));
    }

    /** The provider of the given tenant, {@code local} or {@code cloud}. */
    public String providerOf(Long tenantId) {
        return TenantContext.callAs(tenantId, () -> isCloud() ? CLOUD : LOCAL);
    }

    /**
     * Switches the given tenant's LLM.
     *
     * @throws IllegalArgumentException for anything but {@code local} / {@code cloud}
     * @throws IllegalStateException    when switching to cloud without a configured API key
     */
    public void setProvider(Long tenantId, String provider) {
        String value = normalise(provider);
        if (!PROVIDERS.contains(value)) {
            throw new IllegalArgumentException("LLM provider must be 'local' or 'cloud'.");
        }
        if (CLOUD.equals(value) && !cloudLlmClient.isConfigured()) {
            throw new IllegalStateException("The cloud LLM is not configured: put ANTHROPIC_API_KEY in SECRETS_DIR first.");
        }
        TenantContext.runAs(tenantId, () -> settingsService.setString(KEY_PROVIDER, value));
    }

    private static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
