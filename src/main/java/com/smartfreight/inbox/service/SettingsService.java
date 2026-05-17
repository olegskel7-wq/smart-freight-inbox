package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.AppSetting;
import com.smartfreight.inbox.repository.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {

    private static final String KEY_DRAFT_ENABLED = "notification.draft-enabled";

    private final AppSettingRepository repo;

    public SettingsService(AppSettingRepository repo) {
        this.repo = repo;
    }

    public boolean isDraftEnabled() {
        return repo.findById(KEY_DRAFT_ENABLED)
                .map(s -> "true".equalsIgnoreCase(s.getValue()))
                .orElse(true);
    }

    @Transactional
    public void setDraftEnabled(boolean enabled) {
        AppSetting setting = repo.findById(KEY_DRAFT_ENABLED)
                .orElse(new AppSetting(KEY_DRAFT_ENABLED, "true"));
        setting.setValue(enabled ? "true" : "false");
        repo.save(setting);
    }
}
