package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.SettingsResponse;
import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.integration.MailboxImapAdapterManager;
import com.smartfreight.inbox.scheduler.QuoteFollowUpScheduler;
import com.smartfreight.inbox.scheduler.StatusWatchScheduler;
import com.smartfreight.inbox.service.AutoSendService;
import com.smartfreight.inbox.service.MailboxManagementService;
import com.smartfreight.inbox.service.OllamaClientProvider;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.service.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

/**
 * JSON API backing the React {@code /settings} page (Phase 3.2). It mirrors {@link SettingsController}
 * (the Thymeleaf settings UI) but speaks DTOs over {@code /api/settings} and is secured by the stateless
 * resource-server chain (bearer JWT). The two controllers run side by side during the migration; this one
 * collapses to the only one when Thymeleaf is removed.
 *
 * <p>Validation matches the form controller exactly (threshold clamped to [0,1], type allow-list
 * normalised against {@link TicketType}, delay a non-negative whole number, Ollama URL scheme-checked).
 * A rejected value answers {@code 400}/{@code 404}/{@code 409} with a plain message; a successful mutation
 * returns the refreshed {@link SettingsResponse} so the SPA re-renders without a second round-trip.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsApiController {

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
    private final SecretCipher secretCipher;

    public SettingsApiController(SettingsService settingsService,
                                 MailboxManagementService mailboxService,
                                 MailboxImapAdapterManager adapterManager,
                                 SecretCipher secretCipher) {
        this.settingsService = settingsService;
        this.mailboxService = mailboxService;
        this.adapterManager = adapterManager;
        this.secretCipher = secretCipher;
    }

    /** Request body for {@code POST /api/settings/autosend}. */
    public record AutoSendRequest(boolean enabled, String threshold, List<String> types) {}

    /** Request body for {@code POST /api/settings/statusupdate}. */
    public record StatusUpdateRequest(boolean enabled, String milestones) {}

    /** Request body for {@code POST /api/settings/quotefollowup}. */
    public record QuoteFollowUpRequest(boolean enabled, String delayHours) {}

    /** Request body for {@code POST /api/settings/mailboxes}. */
    public record MailboxRequest(String host, Integer port, String username, String password,
                                 String smtpHost, Integer smtpPort, String fromAddress) {}

    /** Request body for {@code POST /api/settings/mailboxes/{id}/toggle}. */
    public record ToggleRequest(boolean enabled) {}

    /** Request body for {@code POST /api/settings/ollama}. */
    public record OllamaRequest(String ollamaBaseUrl) {}

    /** Request body for {@code POST /api/settings/notifications}. */
    public record NotificationsRequest(boolean draftEnabled) {}

    /** Request body for {@code POST /api/settings/prompts}. */
    public record PromptsRequest(String extractionPrompt, String draftingPrompt) {}

    /** GET /api/settings — the full settings snapshot for the current tenant. */
    @GetMapping
    public SettingsResponse get() {
        return buildResponse();
    }

    /**
     * POST /api/settings/autosend — updates the Phase 1.6 auto-send gates. The threshold is clamped to
     * [0,1]; the type list is normalised against {@link TicketType} (unknown names dropped, mirroring the
     * tolerant read path). Enabling auto-send with no allowed types is saved as-is — the SPA flags it.
     */
    @PostMapping("/autosend")
    public SettingsResponse saveAutoSend(@RequestBody AutoSendRequest request) {
        double parsed;
        try {
            parsed = Math.min(1.0, Math.max(0.0, Double.parseDouble(request.threshold().trim())));
        } catch (NullPointerException | NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Threshold must be a number between 0 and 1.");
        }

        List<String> validTypes = (request.types() == null ? List.<String>of() : request.types()).stream()
                .map(t -> t.trim().toUpperCase())
                .filter(this::isKnownType)
                .toList();

        settingsService.setString(AutoSendService.FEATURE_KEY, request.enabled() ? "true" : "false");
        settingsService.setString(AutoSendService.KEY_THRESHOLD, String.valueOf(parsed));
        settingsService.setString(AutoSendService.KEY_TYPES, String.join(",", validTypes));
        return buildResponse();
    }

    /**
     * POST /api/settings/statusupdate — updates the Phase 2.4 proactive status-update gates. Milestone
     * names are stored upper-cased and de-blanked; enabling with no milestones is rejected so nothing is
     * silently armed with an empty trigger set.
     */
    @PostMapping("/statusupdate")
    public SettingsResponse saveStatusUpdate(@RequestBody StatusUpdateRequest request) {
        String normalised = (request.milestones() == null ? "" : request.milestones()).trim();
        if (request.enabled() && normalised.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Status-update automation is on but no milestones are set — nothing would be sent.");
        }
        settingsService.setString(StatusWatchScheduler.FEATURE_KEY, request.enabled() ? "true" : "false");
        settingsService.setString(StatusWatchScheduler.KEY_MILESTONES, normalised);
        return buildResponse();
    }

    /**
     * POST /api/settings/quotefollowup — updates the Phase 2.5 quote-follow-up gates. The delay is a
     * non-negative whole number of hours; a malformed or negative value is rejected so the stored setting
     * stays parseable by {@link QuoteFollowUpScheduler#delayHours()}.
     */
    @PostMapping("/quotefollowup")
    public SettingsResponse saveQuoteFollowUp(@RequestBody QuoteFollowUpRequest request) {
        long parsed;
        try {
            parsed = Long.parseLong(request.delayHours().trim());
        } catch (NullPointerException | NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Delay must be a whole number of hours.");
        }
        if (parsed < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Delay cannot be negative.");
        }
        settingsService.setString(QuoteFollowUpScheduler.FEATURE_KEY, request.enabled() ? "true" : "false");
        settingsService.setString(QuoteFollowUpScheduler.KEY_DELAY_HOURS, String.valueOf(parsed));
        return buildResponse();
    }

    /**
     * POST /api/settings/mailboxes — adds a mailbox for the current tenant (Phase 2.2 S3). The reconciler
     * starts its IMAP adapter within one pass (~30 s) — no restart.
     */
    @PostMapping("/mailboxes")
    public SettingsResponse addMailbox(@RequestBody MailboxRequest request) {
        try {
            int port = request.port() != null ? request.port() : 993;
            mailboxService.create(request.host(), port, request.username(), request.password(),
                    request.smtpHost(), request.smtpPort(), request.fromAddress());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This mailbox (host + username) is already configured.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return buildResponse();
    }

    /** POST /api/settings/mailboxes/{id}/toggle — pauses/resumes a mailbox without losing its credentials. */
    @PostMapping("/mailboxes/{id}/toggle")
    public SettingsResponse toggleMailbox(@PathVariable Long id, @RequestBody ToggleRequest request) {
        try {
            mailboxService.setEnabled(id, request.enabled());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return buildResponse();
    }

    /** DELETE /api/settings/mailboxes/{id} — removes a mailbox; its adapter stops on the next pass. */
    @DeleteMapping("/mailboxes/{id}")
    public ResponseEntity<Void> deleteMailbox(@PathVariable Long id) {
        try {
            mailboxService.delete(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/settings/ollama — saves the tenant's Ollama endpoint override (Phase 2.2 S3). Blank =
     * inherit the platform instance. The URL must be absolute http(s) so the WebClient can build it.
     */
    @PostMapping("/ollama")
    public SettingsResponse saveOllamaUrl(@RequestBody OllamaRequest request) {
        String trimmed = request.ollamaBaseUrl() == null ? "" : request.ollamaBaseUrl().trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ollama URL must start with http:// or https://.");
        }
        settingsService.setString(OllamaClientProvider.KEY_OLLAMA_BASE_URL, trimmed);
        return buildResponse();
    }

    /** POST /api/settings/notifications — toggles operator draft-ready email notifications. */
    @PostMapping("/notifications")
    public SettingsResponse saveNotifications(@RequestBody NotificationsRequest request) {
        settingsService.setDraftEnabled(request.draftEnabled());
        return buildResponse();
    }

    /**
     * POST /api/settings/prompts — updates the AI prompt templates. Placeholders ({@code {email}},
     * {@code {itemContext}}, {@code {rateContext}}) are substituted at request time, so editing here
     * re-tunes the pipeline without a rebuild.
     */
    @PostMapping("/prompts")
    public SettingsResponse savePrompts(@RequestBody PromptsRequest request) {
        settingsService.setString(SettingsService.KEY_EXTRACTION_PROMPT, request.extractionPrompt());
        settingsService.setString(SettingsService.KEY_DRAFTING_PROMPT, request.draftingPrompt());
        return buildResponse();
    }

    // ---- helpers ----

    private boolean isKnownType(String name) {
        try {
            TicketType.valueOf(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Snapshots the current tenant's settings into the response DTO (mirrors the Thymeleaf model). */
    private SettingsResponse buildResponse() {
        List<SettingsResponse.MailboxDto> mailboxes = mailboxService.findAll().stream()
                .map(m -> SettingsResponse.MailboxDto.from(m, adapterManager.isRunning(m.getId())))
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
                mailboxes);
    }
}
