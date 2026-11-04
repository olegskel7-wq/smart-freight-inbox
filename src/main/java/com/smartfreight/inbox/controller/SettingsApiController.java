package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.SettingsResponse;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.scheduler.QuoteFollowUpScheduler;
import com.smartfreight.inbox.scheduler.StatusWatchScheduler;
import com.smartfreight.inbox.service.AutoSendService;
import com.smartfreight.inbox.service.EmailClassificationService;
import com.smartfreight.inbox.service.OllamaClientProvider;
import com.smartfreight.inbox.service.SettingsService;
import com.smartfreight.inbox.service.TicketProcessingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * JSON API backing the React {@code /settings} page; the mailbox rows live in
 * {@link MailboxSettingsController}.
 *
 * <p>The threshold is clamped to [0,1], the type allow-list normalised against {@link TicketType}, the delay
 * is a non-negative whole number and the Ollama URL scheme-checked. A rejected value answers {@code 400} with
 * a plain message; a successful mutation returns the refreshed {@link SettingsResponse} so the SPA re-renders
 * without a second round trip.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsApiController {

    private final SettingsService settingsService;
    private final SettingsSnapshotAssembler snapshotAssembler;

    SettingsApiController(SettingsService settingsService, SettingsSnapshotAssembler snapshotAssembler) {
        this.settingsService = settingsService;
        this.snapshotAssembler = snapshotAssembler;
    }

    /** Request body for {@code POST /api/settings/autosend}. */
    public record AutoSendRequest(boolean enabled, String threshold, List<String> types) {}

    /** Request body for {@code POST /api/settings/statusupdate}. */
    public record StatusUpdateRequest(boolean enabled, String milestones) {}

    /** Request body for {@code POST /api/settings/quotefollowup}. */
    public record QuoteFollowUpRequest(boolean enabled, String delayHours) {}

    /** Request body for {@code POST /api/settings/ollama}. */
    public record OllamaRequest(String ollamaBaseUrl) {}

    /** Request body for {@code POST /api/settings/notifications}. */
    public record NotificationsRequest(boolean draftEnabled) {}

    /** Request body for {@code POST /api/settings/routing}. */
    public record RoutingRequest(boolean smartRoutingEnabled, String escalationAssignee) {}

    /** Request body for {@code POST /api/settings/prompts}. */
    public record PromptsRequest(String extractionPrompt, String draftingPrompt) {}

    /** GET /api/settings — the full settings snapshot for the current tenant. */
    @GetMapping
    public SettingsResponse get() {
        return snapshotAssembler.snapshot();
    }

    /**
     * POST /api/settings/autosend — updates the auto-send gates. The threshold is clamped to
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
        return snapshotAssembler.snapshot();
    }

    /**
     * POST /api/settings/statusupdate — updates the proactive status-update gates. The milestone
     * list is stored trimmed as a whole; enabling with no milestones is rejected so nothing is
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
        return snapshotAssembler.snapshot();
    }

    /**
     * POST /api/settings/quotefollowup — updates the quote-follow-up gates. The delay is a
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
        return snapshotAssembler.snapshot();
    }

    /**
     * POST /api/settings/routing — updates the smart-routing gate and the escalation assignee.
     * The assignee is a Keycloak user UUID (the SPA picks it from the operator list) or blank to clear it;
     * it is stored as-is (trimmed) — an unknown id simply never matches a real operator and never gets
     * auto-assigned, so no extra validation is needed here. With the flag off the classifier stops
     * extracting urgency/sentiment (existing tickets keep their last values).
     */
    @PostMapping("/routing")
    public SettingsResponse saveRouting(@RequestBody RoutingRequest request) {
        settingsService.setString(EmailClassificationService.FEATURE_SMART_ROUTING,
                request.smartRoutingEnabled() ? "true" : "false");
        String assignee = request.escalationAssignee() == null ? "" : request.escalationAssignee().trim();
        settingsService.setString(TicketProcessingService.SETTING_ESCALATION_ASSIGNEE, assignee);
        return snapshotAssembler.snapshot();
    }

    /**
     * POST /api/settings/ollama — saves the tenant's Ollama endpoint override. Blank =
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
        return snapshotAssembler.snapshot();
    }

    /** POST /api/settings/notifications — toggles operator draft-ready email notifications. */
    @PostMapping("/notifications")
    public SettingsResponse saveNotifications(@RequestBody NotificationsRequest request) {
        settingsService.setDraftEnabled(request.draftEnabled());
        return snapshotAssembler.snapshot();
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
        return snapshotAssembler.snapshot();
    }

    private boolean isKnownType(String name) {
        try {
            TicketType.valueOf(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
