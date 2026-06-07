package com.smartfreight.inbox.controller;

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
        return "settings";
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
