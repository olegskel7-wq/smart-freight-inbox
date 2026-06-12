package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.AppSetting;
import com.smartfreight.inbox.entity.TenantSetting;
import com.smartfreight.inbox.repository.AppSettingRepository;
import com.smartfreight.inbox.repository.TenantSettingRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resolution order of the Phase 2.2 S2 settings chain:
 * tenant_setting (current tenant) → app_setting (platform default) → code default.
 *
 * TenantContext is a plain ThreadLocal — runAs/runAsSystem work without Spring here.
 * The @TenantId scoping of the tenant repository itself is integration-tested in
 * {@link com.smartfreight.inbox.tenancy.SettingsTenantIsolationTest}.
 */
@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    private static final long TENANT = 7L;

    @Mock
    private AppSettingRepository appSettings;

    @Mock
    private TenantSettingRepository tenantSettings;

    private SettingsService service() {
        return new SettingsService(appSettings, tenantSettings);
    }

    @Test
    void tenantOverride_winsOverPlatformDefault() {
        when(tenantSettings.findByKey("ai.extraction-prompt"))
                .thenReturn(Optional.of(new TenantSetting("ai.extraction-prompt", "tenant prompt")));

        String resolved = TenantContext.callAs(TENANT,
                () -> service().getString("ai.extraction-prompt", "code default"));

        assertThat(resolved).isEqualTo("tenant prompt");
        verify(appSettings, never()).findById(any());
    }

    @Test
    void missingOverride_fallsBackToPlatformDefault() {
        when(tenantSettings.findByKey("ai.extraction-prompt")).thenReturn(Optional.empty());
        when(appSettings.findById("ai.extraction-prompt"))
                .thenReturn(Optional.of(new AppSetting("ai.extraction-prompt", "platform prompt")));

        String resolved = TenantContext.callAs(TENANT,
                () -> service().getString("ai.extraction-prompt", "code default"));

        assertThat(resolved).isEqualTo("platform prompt");
    }

    @Test
    void blankOverride_fallsBackToPlatformDefault() {
        when(tenantSettings.findByKey("key")).thenReturn(Optional.of(new TenantSetting("key", "  ")));
        when(appSettings.findById("key")).thenReturn(Optional.of(new AppSetting("key", "platform")));

        assertThat(TenantContext.callAs(TENANT, () -> service().getString("key", "default")))
                .isEqualTo("platform");
    }

    @Test
    void nothingStored_resolvesToCodeDefault() {
        when(tenantSettings.findByKey("key")).thenReturn(Optional.empty());
        when(appSettings.findById("key")).thenReturn(Optional.empty());

        assertThat(TenantContext.callAs(TENANT, () -> service().getString("key", "code default")))
                .isEqualTo("code default");
    }

    @Test
    void systemContext_skipsTenantLookup() {
        // Cross-tenant work (schedulers, relay) must read platform defaults only — with the
        // @TenantId filter off, a by-key lookup would match one row per tenant.
        when(appSettings.findById("feature.sla-timer"))
                .thenReturn(Optional.of(new AppSetting("feature.sla-timer", "false")));

        boolean enabled = TenantContext.callAsSystem(
                () -> service().isFeatureEnabled("feature.sla-timer", true));

        assertThat(enabled).isFalse();
        verify(tenantSettings, never()).findByKey(any());
    }

    @Test
    void featureFlag_parsesTenantOverride() {
        when(tenantSettings.findByKey("feature.auto-send"))
                .thenReturn(Optional.of(new TenantSetting("feature.auto-send", "TRUE")));

        assertThat(TenantContext.callAs(TENANT,
                () -> service().isFeatureEnabled("feature.auto-send", false))).isTrue();
    }

    @Test
    void setString_updatesExistingTenantOverride() {
        TenantSetting existing = new TenantSetting("key", "old");
        when(tenantSettings.findByKey("key")).thenReturn(Optional.of(existing));

        TenantContext.runAs(TENANT, () -> service().setString("key", "new"));

        ArgumentCaptor<TenantSetting> saved = ArgumentCaptor.forClass(TenantSetting.class);
        verify(tenantSettings).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(saved.getValue().getValue()).isEqualTo("new");
    }

    @Test
    void setString_createsOverrideWhenAbsent() {
        when(tenantSettings.findByKey("key")).thenReturn(Optional.empty());

        TenantContext.runAs(TENANT, () -> service().setString("key", "value"));

        ArgumentCaptor<TenantSetting> saved = ArgumentCaptor.forClass(TenantSetting.class);
        verify(tenantSettings).save(saved.capture());
        assertThat(saved.getValue().getKey()).isEqualTo("key");
        assertThat(saved.getValue().getValue()).isEqualTo("value");
    }

    @Test
    void setString_inSystemContext_failsLoudly() {
        assertThatThrownBy(() -> TenantContext.runAsSystem(() -> service().setString("key", "value")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system context");
        verify(tenantSettings, never()).save(any());
    }
}
