package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.repository.CarrierRateRepository;
import com.smartfreight.inbox.service.RateSheetService;
import com.smartfreight.inbox.service.SettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Carrier rate sheet management (Phase 1.2): table view, CSV upload, row delete.
 *
 * <p>CSV import is delegated to {@link RateSheetService}; per-row errors come back as a flash
 * attribute so the operator sees exactly which lines were rejected without losing the good rows.
 */
@Controller
@RequestMapping("/rates")
public class RateSheetController {

    private final RateSheetService rateSheetService;
    private final CarrierRateRepository carrierRateRepository;
    private final SettingsService settingsService;

    public RateSheetController(RateSheetService rateSheetService,
                               CarrierRateRepository carrierRateRepository,
                               SettingsService settingsService) {
        this.rateSheetService = rateSheetService;
        this.carrierRateRepository = carrierRateRepository;
        this.settingsService = settingsService;
    }

    @GetMapping
    public String ratesPage(Model model) {
        model.addAttribute("rates", carrierRateRepository.findAllByOrderByCarrierAscOriginAscDestinationAsc());
        model.addAttribute("featureEnabled",
                settingsService.isFeatureEnabled(RateSheetService.FEATURE_KEY, true));
        return "rates";
    }

    @PostMapping("/upload")
    public String uploadCsv(@RequestParam("file") MultipartFile file,
                            RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please choose a CSV file to upload.");
            return "redirect:/rates";
        }
        try {
            RateSheetService.ImportResult result = rateSheetService.importCsv(file.getInputStream());
            redirectAttributes.addFlashAttribute("successMessage", String.format(
                    "Imported %d rate(s): %d new, %d updated.",
                    result.total(), result.inserted(), result.updated()));
            if (!result.errors().isEmpty()) {
                redirectAttributes.addFlashAttribute("importErrors", result.errors());
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Import failed: " + e.getMessage());
        }
        return "redirect:/rates";
    }

    @PostMapping("/{id}/delete")
    public String deleteRate(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        carrierRateRepository.deleteById(id);
        redirectAttributes.addFlashAttribute("successMessage", "Rate deleted.");
        return "redirect:/rates";
    }

    @PostMapping("/clear")
    public String clearAll(RedirectAttributes redirectAttributes) {
        long count = carrierRateRepository.count();
        carrierRateRepository.deleteAll();
        redirectAttributes.addFlashAttribute("successMessage", "Removed all " + count + " rate(s).");
        return "redirect:/rates";
    }
}
