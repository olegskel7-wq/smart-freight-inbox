package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.service.TenantManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Platform admin UI for tenant onboarding (Phase 2.2 S2).
 *
 * <p>Access: SUPER_ADMIN only — enforced by the {@code /admin/tenants/**} matcher in
 * SecurityConfig, which precedes the broader OPERATOR {@code /admin/**} rule.
 */
@Controller
@RequestMapping("/admin/tenants")
public class TenantAdminController {

    private static final Logger log = LoggerFactory.getLogger(TenantAdminController.class);

    private final TenantManagementService tenantManagementService;

    public TenantAdminController(TenantManagementService tenantManagementService) {
        this.tenantManagementService = tenantManagementService;
    }

    @GetMapping
    public String listTenants(Model model) {
        model.addAttribute("tenants", tenantManagementService.overview());
        return "admin-tenants";
    }

    @PostMapping("/create")
    public String createTenant(@RequestParam String name,
                               @RequestParam String adminEmail,
                               @RequestParam String adminPassword,
                               RedirectAttributes redirectAttributes) {
        try {
            Tenant tenant = tenantManagementService.createTenant(name, adminEmail, adminPassword);
            log.info("Tenant created: id={} name={}", tenant.getId(), tenant.getName());
            redirectAttributes.addFlashAttribute("successMessage",
                    "Tenant '" + tenant.getName() + "' created with operator " + adminEmail.trim().toLowerCase() + ".");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/tenants";
    }
}
