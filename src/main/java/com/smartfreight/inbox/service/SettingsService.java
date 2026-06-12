package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.AppSetting;
import com.smartfreight.inbox.entity.TenantSetting;
import com.smartfreight.inbox.repository.AppSettingRepository;
import com.smartfreight.inbox.repository.TenantSettingRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Reads and writes runtime-mutable application settings.
 *
 * <p>Phase 2.2 S2 — settings are resolved per tenant with a uniform override chain:
 * <ol>
 *   <li>{@code tenant_setting} — the current tenant's override (written by {@code /settings})</li>
 *   <li>{@code app_setting} — the platform-wide default (seeded by migrations)</li>
 *   <li>the code-level {@code defaultValue} passed by the caller</li>
 * </ol>
 * Call sites are unchanged from the global-settings era: the tenant dimension comes from
 * {@link TenantContext}, exactly as the Phase 1 feature-flag convention promised.
 *
 * <p><b>System context</b> (schedulers, outbox relay, metrics) skips step 1 — cross-tenant
 * work has no single tenant to read an override for, so it sees platform defaults only.
 * Per-tenant values still apply wherever they matter: the pipeline consumer, web requests
 * and ingestion all run inside a tenant scope.
 *
 * <p>Writes always target {@code tenant_setting}; {@code app_setting} is mutated only by
 * migrations now and acts as the inherited baseline for every tenant.
 */
@Service
public class SettingsService {

    private static final String KEY_DRAFT_ENABLED = "notification.draft-enabled";

    /** AI extraction prompt template ({@code {email}} placeholder). Seeded by migration V9. */
    public static final String KEY_EXTRACTION_PROMPT = "ai.extraction-prompt";

    /** AI drafting prompt template ({@code {itemContext}}, {@code {email}} placeholders). Seeded by migration V9. */
    public static final String KEY_DRAFTING_PROMPT = "ai.drafting-prompt";

    private final AppSettingRepository appSettings;
    private final TenantSettingRepository tenantSettings;

    public SettingsService(AppSettingRepository appSettings, TenantSettingRepository tenantSettings) {
        this.appSettings = appSettings;
        this.tenantSettings = tenantSettings;
    }

    /**
     * Reads a free-form string setting through the override chain, falling back to
     * {@code defaultValue} when neither the tenant nor the platform has a non-blank value.
     *
     * @param key          setting key
     * @param defaultValue value returned when no stored value applies
     * @return resolved value
     */
    public String getString(String key, String defaultValue) {
        return resolve(key).orElse(defaultValue);
    }

    /**
     * Upserts the <b>current tenant's</b> override for a free-form string setting.
     * The platform default in {@code app_setting} is never touched.
     *
     * @param key   setting key
     * @param value new value
     */
    @Transactional
    public void setString(String key, String value) {
        if (TenantContext.isSystem()) {
            // Writes are tenant-scoped by definition; in the system context the @TenantId
            // filter is off and the insert has no tenant to stamp. No caller does this —
            // fail loudly if one ever appears.
            throw new IllegalStateException("Settings cannot be written from the system context: " + key);
        }
        TenantSetting setting = tenantSettings.findByKey(key).orElse(new TenantSetting(key, value));
        setting.setValue(value);
        tenantSettings.save(setting);
    }

    /**
     * Generic feature-flag check. Resolves a boolean {@code feature.*} setting through the
     * override chain, treating an absent or blank value as {@code defaultValue}.
     *
     * <p>This is the seam the Phase 1 flag convention was built for: the same keys call
     * sites used globally now carry a tenant dimension — a tenant toggling a flag on
     * {@code /settings} affects only their own pipeline. Plan-based gating (which flags a
     * subscription tier may even touch) is a later layer on top of this resolution.
     *
     * @param featureKey   the flag key, conventionally prefixed {@code feature.}
     *                     (e.g. {@code "feature.attachment-parsing"})
     * @param defaultValue value returned when no stored value applies
     * @return whether the feature is enabled
     */
    public boolean isFeatureEnabled(String featureKey, boolean defaultValue) {
        return resolve(featureKey)
                .map(v -> "true".equalsIgnoreCase(v.trim()))
                .orElse(defaultValue);
    }

    /**
     * Returns whether operator draft notifications are enabled for the current tenant.
     * Defaults to {@code true} so a fresh deployment behaves predictably.
     */
    public boolean isDraftEnabled() {
        return isFeatureEnabled(KEY_DRAFT_ENABLED, true);
    }

    /**
     * Updates the current tenant's draft-enabled flag.
     *
     * @param enabled new value
     */
    @Transactional
    public void setDraftEnabled(boolean enabled) {
        setString(KEY_DRAFT_ENABLED, enabled ? "true" : "false");
    }

    /** Override chain: tenant value (skipped in system context) → platform default. */
    private Optional<String> resolve(String key) {
        return tenantValue(key).or(() -> platformDefault(key));
    }

    private Optional<String> tenantValue(String key) {
        if (TenantContext.isSystem()) {
            // Cross-tenant work has no tenant to read an override for. Looking the key up
            // anyway would be worse than wrong: with the @TenantId filter off, one key
            // matches a row per tenant. Unset context still resolves (= default tenant).
            return Optional.empty();
        }
        return tenantSettings.findByKey(key)
                .map(TenantSetting::getValue)
                .filter(v -> !v.isBlank());
    }

    private Optional<String> platformDefault(String key) {
        return appSettings.findById(key)
                .map(AppSetting::getValue)
                .filter(v -> !v.isBlank());
    }
}
