package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.config.DbUserDetailsService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Model attributes shared by every Thymeleaf view.
 *
 * <p>{@code isSuperAdmin} gates the Tenants link in the navbar fragment. There is no
 * thymeleaf-extras-springsecurity dialect on the classpath (and its Boot 4 / Security 7
 * compatibility is the same gamble the Resilience4j starter lost), so the check lives
 * here instead of a {@code sec:authorize} attribute.
 */
@ControllerAdvice
public class GlobalModelAttributes {

    @ModelAttribute("isSuperAdmin")
    public boolean isSuperAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + DbUserDetailsService.SUPER_ADMIN_ROLE).equals(a.getAuthority()));
    }
}
