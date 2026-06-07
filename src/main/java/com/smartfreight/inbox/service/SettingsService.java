package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.AppSetting;
import com.smartfreight.inbox.repository.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes runtime-mutable application settings stored in the {@code app_setting} table.
 *
 * <p>Currently exposes a single toggle — the operator draft notification flag. Centralising
 * the read path here means callers ({@link DraftNotificationService}, {@link GmailDraftService})
 * always see the latest value without restarting the JVM, unlike {@code @Value}-backed config.
 *
 * <p>Seeded from migration {@code V7__app_settings_and_user_management.sql} with the default
 * {@code notification.draft-enabled=true}.
 */
@Service
public class SettingsService {

    private static final String KEY_DRAFT_ENABLED = "notification.draft-enabled";

    /** AI extraction prompt template ({@code {email}} placeholder). Seeded by migration V9. */
    public static final String KEY_EXTRACTION_PROMPT = "ai.extraction-prompt";

    /** AI drafting prompt template ({@code {itemContext}}, {@code {email}} placeholders). Seeded by migration V9. */
    public static final String KEY_DRAFTING_PROMPT = "ai.drafting-prompt";

    private final AppSettingRepository repo;

    public SettingsService(AppSettingRepository repo) {
        this.repo = repo;
    }

    /**
     * Reads a free-form string setting, falling back to {@code defaultValue} when the row is
     * missing or blank. Used for runtime-tunable values like AI prompt templates so a fresh
     * deployment (or a wiped row) still behaves correctly using the code-level default.
     *
     * @param key          setting key
     * @param defaultValue value returned when the row is absent or blank
     * @return stored value, or {@code defaultValue}
     */
    public String getString(String key, String defaultValue) {
        return repo.findById(key)
                .map(AppSetting::getValue)
                .filter(v -> !v.isBlank())
                .orElse(defaultValue);
    }

    /**
     * Upserts a free-form string setting, creating the row if it does not yet exist.
     *
     * @param key   setting key
     * @param value new value
     */
    @Transactional
    public void setString(String key, String value) {
        AppSetting setting = repo.findById(key).orElse(new AppSetting(key, value));
        setting.setValue(value);
        repo.save(setting);
    }

    /**
     * Returns whether operator draft notifications are enabled.
     * Defaults to {@code true} when the row is missing so a fresh deployment behaves predictably.
     */
    public boolean isDraftEnabled() {
        return repo.findById(KEY_DRAFT_ENABLED)
                .map(s -> "true".equalsIgnoreCase(s.getValue()))
                .orElse(true);
    }

    /**
     * Updates the draft-enabled flag, creating the row if it does not yet exist.
     *
     * @param enabled new value
     */
    @Transactional
    public void setDraftEnabled(boolean enabled) {
        AppSetting setting = repo.findById(KEY_DRAFT_ENABLED)
                .orElse(new AppSetting(KEY_DRAFT_ENABLED, "true"));
        setting.setValue(enabled ? "true" : "false");
        repo.save(setting);
    }
}
