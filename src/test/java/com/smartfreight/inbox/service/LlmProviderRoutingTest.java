package com.smartfreight.inbox.service;

import com.smartfreight.inbox.tenancy.TenantContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Which provider serves a completion, and who may switch a tenant to the cloud one. */
class LlmProviderRoutingTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final SettingsService settings = mock(SettingsService.class);
    private final CloudLlmClient cloud = mock(CloudLlmClient.class);
    private final LlmProviderSettings providers = new LlmProviderSettings(settings, cloud);

    private void tenantProvider(String value) {
        when(settings.getString(LlmProviderSettings.KEY_PROVIDER, LlmProviderSettings.LOCAL)).thenReturn(value);
    }

    // ---- routing ---------------------------------------------------------------------------

    @Test
    void localTenant_isServedByOllama_withTheCallersModel() {
        tenantProvider("local");
        OllamaStub ollama = new OllamaStub();
        ollama.answers(Map.of("response", "MSCU1234567", "done", true));

        Optional<String> text = new LlmClient(ollama.provider, providers, cloud)
                .generate("extraction", "qwen2.5:7b", "prompt", TIMEOUT);

        assertThat(text).contains("MSCU1234567");
        assertThat(ollama.sentBody().get("model")).isEqualTo("qwen2.5:7b");
        verifyNoInteractions(cloud);
    }

    @Test
    void cloudTenant_isServedByTheCloudClient_andOllamaIsNotCalled() {
        tenantProvider(" Cloud ");
        OllamaStub ollama = new OllamaStub();
        when(cloud.generate("drafting", "prompt", TIMEOUT)).thenReturn("Dear Sue");

        Optional<String> text = new LlmClient(ollama.provider, providers, cloud)
                .generate("drafting", "llama3.1:8b", "prompt", TIMEOUT);

        assertThat(text).contains("Dear Sue");
        verifyNoInteractions(ollama.webClient);
    }

    @Test
    void unknownOrMissingSetting_staysLocal() {
        tenantProvider("gpt");
        assertThat(providers.isCloud()).isFalse();
        tenantProvider("local");
        assertThat(providers.isCloud()).isFalse();
    }

    // ---- switching ------------------------------------------------------------------------

    @Test
    void setProvider_writesTheTenantsSetting_inThatTenantsContext() {
        when(cloud.isConfigured()).thenReturn(true);
        Long[] writtenAs = new Long[1];
        org.mockito.Mockito.doAnswer(i -> {
            writtenAs[0] = TenantContext.tenantIdOrNull();
            return null;
        }).when(settings).setString(LlmProviderSettings.KEY_PROVIDER, "cloud");

        providers.setProvider(42L, "CLOUD");

        assertThat(writtenAs[0]).isEqualTo(42L);
    }

    @Test
    void setProvider_unknownValue_isRefused() {
        assertThatThrownBy(() -> providers.setProvider(42L, "gpt"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(settings, never()).setString(anyString(), any());
    }

    @Test
    void setProvider_cloudWithoutAKey_isRefused_localStillWorks() {
        when(cloud.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> providers.setProvider(42L, "cloud"))
                .isInstanceOf(IllegalStateException.class);
        verify(settings, never()).setString(anyString(), any());

        providers.setProvider(42L, "local");
        verify(settings).setString(LlmProviderSettings.KEY_PROVIDER, "local");
    }
}
