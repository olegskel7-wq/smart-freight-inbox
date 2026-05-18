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

    private final AppSettingRepository repo;

    public SettingsService(AppSettingRepository repo) {
        this.repo = repo;
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
