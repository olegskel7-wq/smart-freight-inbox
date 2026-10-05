package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.integration.MailboxHealthMonitor.MailboxHealth;

import java.time.Instant;
import java.util.List;

/**
 * GET {@code /api/settings} payload (Phase 3.2) — everything the React Settings page renders, mirroring
 * the model the Thymeleaf {@code settings.html} was given. Stored CSV/number settings are passed through
 * as their raw strings (the SPA edits them the same way the form did); the auto-send type allow-list is
 * split into a list for the checkboxes. The platform SMTP block is read-only (environment-sourced).
 */
public record SettingsResponse(
        boolean cipherConfigured,
        String platformOllamaUrl,
        String ollamaUrlOverride,
        String smtpHost,
        int smtpPort,
        String smtpUser,
        boolean draftEnabled,
        String extractionPrompt,
        String draftingPrompt,
        boolean autoSendEnabled,
        String autoSendThreshold,
        List<String> autoSendTypes,
        List<String> allTicketTypes,
        boolean statusUpdateEnabled,
        String statusUpdateMilestones,
        boolean quoteFollowUpEnabled,
        String quoteFollowUpDelayHours,
        boolean smartRoutingEnabled,
        String escalationAssignee,
        List<MailboxDto> mailboxes) {

    /**
     * Row view of a tenant mailbox. {@code sendsAs} is the address replies go out from when this mailbox
     * has its own SMTP (else null → the SPA shows the platform fallback); {@code running} reflects whether
     * the IMAP adapter is registered yet (the reconciler starts it within ~30 s). {@code loginOk} is the
     * last login probe's verdict (Gate B4; null = not probed yet), with the time of the last good login
     * and the masked failure reason. The raw SMTP fields seed the edit form (Gate B15); the password is
     * never returned.
     */
    public record MailboxDto(
            long id,
            String host,
            int port,
            String username,
            String sendsAs,
            boolean enabled,
            boolean running,
            Boolean loginOk,
            Instant lastLoginOkAt,
            String loginError,
            String smtpHost,
            Integer smtpPort,
            String fromAddress) {

        public static MailboxDto from(TenantMailbox m, boolean running, MailboxHealth health) {
            String sendsAs = m.getSmtpHost() == null ? null : m.effectiveFromAddress();
            return new MailboxDto(
                    m.getId(), m.getHost(), m.getPort(), m.getUsername(),
                    sendsAs, m.isEnabled(), running,
                    health == null ? null : health.up(),
                    health == null ? null : health.lastSuccessAt(),
                    health == null ? null : health.lastError(),
                    m.getSmtpHost(), m.getSmtpPort(), m.getFromAddress());
        }
    }
}
