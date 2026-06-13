package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.integration.MailboxImapAdapterManager;
import com.smartfreight.inbox.service.AutoSendService;
import com.smartfreight.inbox.service.MailboxManagementService;
import com.smartfreight.inbox.service.OllamaClientProvider;
import com.smartfreight.inbox.scheduler.StatusWatchScheduler;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.service.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/settings")
public class SettingsController {

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

    public SettingsController(SettingsService settingsService,
                              MailboxManagementService mailboxService,
                              MailboxImapAdapterManager adapterManager,
                              SecretCipher secretCipher) {
        this.settingsService = settingsService;
        this.mailboxService = mailboxService;
        this.adapterManager = adapterManager;
        this.secretCipher = secretCipher;
    }

    @GetMapping
    public String settingsPage(Model model) {
        // Phase 2.2 S3: mailboxes are tenant-scoped DB rows now, not env vars.
        List<TenantMailbox> mailboxes = mailboxService.findAll();
        Map<Long, Boolean> adapterRunning = new HashMap<>();
        for (TenantMailbox mailbox : mailboxes) {
            adapterRunning.put(mailbox.getId(), adapterManager.isRunning(mailbox.getId()));
        }
        model.addAttribute("mailboxes", mailboxes);
        model.addAttribute("adapterRunning", adapterRunning);
        model.addAttribute("cipherConfigured", secretCipher.isConfigured());
        model.addAttribute("platformOllamaUrl", platformOllamaUrl);
        model.addAttribute("ollamaUrlOverride",
                settingsService.getTenantOverride(OllamaClientProvider.KEY_OLLAMA_BASE_URL).orElse(""));
        model.addAttribute("smtpHost", smtpHost);
        model.addAttribute("smtpPort", smtpPort);
        model.addAttribute("smtpUser", smtpUser);
        model.addAttribute("draftEnabled", settingsService.isDraftEnabled());
        model.addAttribute("extractionPrompt",
                settingsService.getString(SettingsService.KEY_EXTRACTION_PROMPT, ""));
        model.addAttribute("draftingPrompt",
                settingsService.getString(SettingsService.KEY_DRAFTING_PROMPT, ""));
        model.addAttribute("autoSendEnabled",
                settingsService.isFeatureEnabled(AutoSendService.FEATURE_KEY, false));
        model.addAttribute("autoSendThreshold",
                settingsService.getString(AutoSendService.KEY_THRESHOLD, "0.8"));
        model.addAttribute("autoSendTypes",
                settingsService.getString(AutoSendService.KEY_TYPES, "STATUS_UPDATE"));
        model.addAttribute("allTicketTypes", TicketType.values());
        model.addAttribute("statusUpdateEnabled",
                settingsService.isFeatureEnabled(StatusWatchScheduler.FEATURE_KEY, false));
        model.addAttribute("statusUpdateMilestones",
                settingsService.getString(StatusWatchScheduler.KEY_MILESTONES,
                        StatusWatchScheduler.DEFAULT_MILESTONES));
        return "settings";
    }

    /**
     * Updates the auto-send gates (Phase 1.6). The threshold is clamped to [0,1] here so the
     * stored value is always sane; the type list is normalised against {@link TicketType} —
     * unknown names are dropped rather than rejected, mirroring the tolerant read path in
     * {@link AutoSendService#allowedTypes}.
     */
    @PostMapping("/autosend")
    public String saveAutoSend(@RequestParam(name = "autoSendEnabled", defaultValue = "false") boolean enabled,
                               @RequestParam("autoSendThreshold") String threshold,
                               @RequestParam(name = "autoSendTypes", required = false) java.util.List<String> types,
                               RedirectAttributes redirectAttributes) {
        double parsed;
        try {
            parsed = Math.min(1.0, Math.max(0.0, Double.parseDouble(threshold.trim())));
        } catch (NumberFormatException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Threshold must be a number between 0 and 1 — settings not saved.");
            return "redirect:/settings";
        }

        java.util.List<String> validTypes = (types == null ? java.util.List.<String>of() : types).stream()
                .map(t -> t.trim().toUpperCase())
                .filter(t -> {
                    try {
                        TicketType.valueOf(t);
                        return true;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                })
                .toList();

        settingsService.setString(AutoSendService.FEATURE_KEY, enabled ? "true" : "false");
        settingsService.setString(AutoSendService.KEY_THRESHOLD, String.valueOf(parsed));
        settingsService.setString(AutoSendService.KEY_TYPES, String.join(",", validTypes));

        if (enabled && validTypes.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Auto-send is on but no ticket types are allowed — nothing will be sent automatically.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage",
                    "Auto-send settings saved" + (enabled ? "" : " (feature is off)") + ".");
        }
        return "redirect:/settings";
    }

    /**
     * Updates the proactive status-update automation gates (Phase 2.4). Milestone names are
     * stored upper-cased and de-blanked; they are matched against the raw backend status names
     * (e.g. AT_PORT, RELEASED, DELIVERED), so no enum validation applies here.
     */
    @PostMapping("/statusupdate")
    public String saveStatusUpdate(@RequestParam(name = "statusUpdateEnabled", defaultValue = "false") boolean enabled,
                                   @RequestParam(name = "statusUpdateMilestones", required = false) String milestones,
                                   RedirectAttributes redirectAttributes) {
        String normalised = (milestones == null ? "" : milestones).trim();
        if (enabled && normalised.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Status-update automation is on but no milestones are set — nothing will be sent.");
            return "redirect:/settings";
        }
        settingsService.setString(StatusWatchScheduler.FEATURE_KEY, enabled ? "true" : "false");
        settingsService.setString(StatusWatchScheduler.KEY_MILESTONES, normalised);
        redirectAttributes.addFlashAttribute("successMessage",
                "Status-update automation saved" + (enabled ? "" : " (feature is off)") + ".");
        return "redirect:/settings";
    }

    /**
     * Adds a mailbox for the current tenant (Phase 2.2 S3). The reconciler starts its
     * IMAP adapter within one pass (~30 s) — no restart.
     */
    @PostMapping("/mailboxes")
    public String addMailbox(@RequestParam("host") String host,
                             @RequestParam(name = "port", defaultValue = "993") int port,
                             @RequestParam("username") String username,
                             @RequestParam("password") String password,
                             RedirectAttributes redirectAttributes) {
        try {
            mailboxService.create(host, port, username, password);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Mailbox added — its IMAP adapter starts within ~30 seconds.");
        } catch (DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "This mailbox (host + username) is already configured.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/settings";
    }

    /** Pauses/resumes a mailbox's adapter without losing the stored credentials. */
    @PostMapping("/mailboxes/{id}/toggle")
    public String toggleMailbox(@PathVariable Long id,
                                @RequestParam("enabled") boolean enabled,
                                RedirectAttributes redirectAttributes) {
        try {
            mailboxService.setEnabled(id, enabled);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Mailbox " + (enabled ? "enabled" : "disabled") + ".");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/mailboxes/{id}/delete")
    public String deleteMailbox(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            mailboxService.delete(id);
            redirectAttributes.addFlashAttribute("successMessage", "Mailbox deleted.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/settings";
    }

    /**
     * Saves the tenant's Ollama endpoint override (Phase 2.2 S3). Blank = inherit the
     * platform instance. The pipeline picks the new endpoint up on the next LLM call;
     * each distinct endpoint gets its own circuit breaker.
     */
    @PostMapping("/ollama")
    public String saveOllamaUrl(@RequestParam("ollamaBaseUrl") String ollamaBaseUrl,
                                RedirectAttributes redirectAttributes) {
        String trimmed = ollamaBaseUrl == null ? "" : ollamaBaseUrl.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Ollama URL must start with http:// or https:// — setting not saved.");
            return "redirect:/settings";
        }
        settingsService.setString(OllamaClientProvider.KEY_OLLAMA_BASE_URL, trimmed);
        redirectAttributes.addFlashAttribute("successMessage", trimmed.isEmpty()
                ? "Ollama endpoint override cleared — using the platform instance."
                : "Ollama endpoint saved: " + trimmed);
        return "redirect:/settings";
    }

    @PostMapping("/notifications")
    public String saveNotifications(@RequestParam(name = "draftEnabled", defaultValue = "false") boolean draftEnabled,
                                    RedirectAttributes redirectAttributes) {
        settingsService.setDraftEnabled(draftEnabled);
        redirectAttributes.addFlashAttribute("successMessage",
                "Notifications " + (draftEnabled ? "enabled" : "disabled") + ".");
        return "redirect:/settings";
    }

    /**
     * Updates the AI prompt templates. Placeholders ({@code {email}}, {@code {itemContext}})
     * are substituted at request time by the AI services, so editing here re-tunes the pipeline
     * for a different vertical without a rebuild.
     */
    @PostMapping("/prompts")
    public String savePrompts(@RequestParam("extractionPrompt") String extractionPrompt,
                              @RequestParam("draftingPrompt") String draftingPrompt,
                              RedirectAttributes redirectAttributes) {
        settingsService.setString(SettingsService.KEY_EXTRACTION_PROMPT, extractionPrompt);
        settingsService.setString(SettingsService.KEY_DRAFTING_PROMPT, draftingPrompt);
        redirectAttributes.addFlashAttribute("successMessage", "AI prompts saved.");
        return "redirect:/settings";
    }
}
