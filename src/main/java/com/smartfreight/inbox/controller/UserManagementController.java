package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.service.UserManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/users")
public class UserManagementController {

    private static final Logger log = LoggerFactory.getLogger(UserManagementController.class);

    private final UserManagementService userManagementService;

    public UserManagementController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping
    public String listUsers(Model model) {
        model.addAttribute("users", userManagementService.findAll());
        return "users";
    }

    @PostMapping("/create")
    public String createUser(@RequestParam String email,
                             @RequestParam String password,
                             @RequestParam(defaultValue = "OPERATOR") String role,
                             RedirectAttributes redirectAttributes) {
        if (password == null || password.length() < 8) {
            redirectAttributes.addFlashAttribute("errorMessage", "Password must be at least 8 characters.");
            return "redirect:/users";
        }
        try {
            userManagementService.createUser(email, password, role);
            log.info("User created: {}", email);
            redirectAttributes.addFlashAttribute("successMessage", "User " + email + " created successfully.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/password")
    public String changePassword(@PathVariable String id,
                                 @RequestParam String newPassword,
                                 RedirectAttributes redirectAttributes) {
        if (newPassword == null || newPassword.length() < 8) {
            redirectAttributes.addFlashAttribute("errorMessage", "Password must be at least 8 characters.");
            return "redirect:/users";
        }
        try {
            userManagementService.changePassword(id, newPassword);
            log.info("Password changed for user id={}", id);
            redirectAttributes.addFlashAttribute("successMessage", "Password updated successfully.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/delete")
    public String deleteUser(@PathVariable String id,
                             @AuthenticationPrincipal OidcUser currentUser,
                             RedirectAttributes redirectAttributes) {
        try {
            userManagementService.deleteUser(id, currentUser.getPreferredUsername());
            log.info("User id={} deleted by {}", id, currentUser.getPreferredUsername());
            redirectAttributes.addFlashAttribute("successMessage", "User deleted.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/users";
    }
}
