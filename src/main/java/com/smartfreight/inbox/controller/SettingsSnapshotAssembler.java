package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.SettingsResponse;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.integration.MailboxHealthMonitor;
import com.smartfreight.inbox.integration.MailboxImapAdapterManager;
import com.smartfreight.inbox.scheduler.QuoteFollowUpScheduler;
import com.smartfreight.inbox.scheduler.StatusWatchScheduler;
import com.smartfreight.inbox.service.AutoSendService;
import com.smartfreight.inbox.service.EmailClassificationService;
import com.smartfreight.inbox.service.MailboxManagementService;
import com.smartfreight.inbox.service.OllamaClientProvider;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.service.SettingsService;
import com.smartfreight.inbox.service.TicketProcessingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Builds the {@link SettingsResponse} snapshot of the current tenant: the answer to {@code GET
 * /api/settings} and to every successful settings mutation, so the SPA re-renders without a second
 * round trip.
 */
@Component
class SettingsSnapshotAssembler {

    @Value("${smtp.host}")
    private String smtpHost;

    @Value("${smtp.port:587}")
    private int smtpPort;

    @Value("${smtp.username}")
    private String smtpUser;

    /** Platform Ollama URL — shown as the inherited default next to the override field. */
    @Value("${ollama.base-url:http://localhost:11434}")
    private String platformOllamaUrl;

    private final SettingsService settingsService;
    private final MailboxManagementService mailboxService;
    private final MailboxImapAdapterManager adapterManager;
    private final MailboxHealthMonitor mailboxHealth;
    private final SecretCipher secretCipher;

    SettingsSnapshotAssembler(SettingsService settingsService,
                              MailboxManagementService mailboxService,
                              MailboxImapAdapterManager adapterManager,
                              MailboxHealthMonitor mailboxHealth,
                              SecretCipher secretCipher) {
        this.settingsService = settingsService;
        this.mailboxService = mailboxService;
        this.adapterManager = adapterManager;
        this.mailboxHealth = mailboxHealth;
        this.secretCipher = secretCipher;
    }

    SettingsResponse snapshot() {
        List<SettingsResponse.MailboxDto> mailboxes = mailboxService.findAll().stream()
                .map(m -> SettingsResponse.MailboxDto.from(m, adapterManager.isRunning(m.getId()),
                        mailboxHealth.health(m.getId()).orElse(null)))
                .toList();

        String autoSendTypesCsv = settingsService.getString(AutoSendService.KEY_TYPES, "STATUS_UPDATE");
        List<String> autoSendTypes = Arrays.stream(autoSendTypesCsv.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();

        List<String> allTypes = Arrays.stream(TicketType.values()).map(Enum::name).toList();

        return new SettingsResponse(
                secretCipher.isConfigured(),
                platformOllamaUrl,
                settingsService.getTenantOverride(OllamaClientProvider.KEY_OLLAMA_BASE_URL).orElse(""),
                smtpHost,
                smtpPort,
                smtpUser,
                settingsService.isDraftEnabled(),
                settingsService.getString(SettingsService.KEY_EXTRACTION_PROMPT, ""),
                settingsService.getString(SettingsService.KEY_DRAFTING_PROMPT, ""),
                settingsService.isFeatureEnabled(AutoSendService.FEATURE_KEY, false),
                settingsService.getString(AutoSendService.KEY_THRESHOLD, "0.8"),
                autoSendTypes,
                allTypes,
                settingsService.isFeatureEnabled(StatusWatchScheduler.FEATURE_KEY, false),
                settingsService.getString(StatusWatchScheduler.KEY_MILESTONES, StatusWatchScheduler.DEFAULT_MILESTONES),
                settingsService.isFeatureEnabled(QuoteFollowUpScheduler.FEATURE_KEY, false),
                settingsService.getString(QuoteFollowUpScheduler.KEY_DELAY_HOURS,
                        String.valueOf(QuoteFollowUpScheduler.DEFAULT_DELAY_HOURS)),
                settingsService.isFeatureEnabled(EmailClassificationService.FEATURE_SMART_ROUTING, true),
                settingsService.getString(TicketProcessingService.SETTING_ESCALATION_ASSIGNEE, ""),
                mailboxes);
    }
}
