package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.repository.TrainingExampleRepository;
import com.smartfreight.inbox.service.FeedbackLoopService;
import com.smartfreight.inbox.service.SettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Captured-corrections view (Phase 3.1): lists the training examples learned from operator edits
 * and lets the operator delete bad ones (dataset curation).
 *
 * <p>Mirrors {@link DocumentController}: thin controller over the repository, flash messages for
 * feedback. The examples are tenant-scoped by {@code @TenantId}, so the page only ever shows the
 * current tenant's corrections.
 */
@Controller
@RequestMapping("/training-examples")
public class TrainingExampleController {

    private final TrainingExampleRepository repository;
    private final SettingsService settingsService;

    public TrainingExampleController(TrainingExampleRepository repository,
                                     SettingsService settingsService) {
        this.repository = repository;
        this.settingsService = settingsService;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("examples", repository.findAllByOrderByCreatedAtDesc());
        model.addAttribute("featureEnabled",
                settingsService.isFeatureEnabled(FeedbackLoopService.FEATURE_KEY, true));
        return "training-examples";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        repository.deleteById(id);
        redirectAttributes.addFlashAttribute("successMessage", "Training example deleted.");
        return "redirect:/training-examples";
    }
}
