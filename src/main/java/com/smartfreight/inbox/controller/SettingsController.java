package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.service.AutoSendService;
import com.smartfreight.inbox.service.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/settings")
public class SettingsController {

    @Value("${spring.mail.host}")
    private String imapHost;

    @Value("${spring.mail.username}")
    private String imapUser;

    @Value("${smtp.host}")
    private String smtpHost;

    @Value("${smtp.port:587}")
    private int smtpPort;

    @Value("${smtp.username}")
    private String smtpUser;

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public String settingsPage(Model model) {
        model.addAttribute("imapHost", imapHost);
        model.addAttribute("imapUser", imapUser);
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
