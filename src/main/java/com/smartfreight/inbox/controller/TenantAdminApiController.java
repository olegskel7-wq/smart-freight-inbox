package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.TenantSummaryDto;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.service.OperatorName;
import com.smartfreight.inbox.service.TenantManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * JSON API backing the React {@code /admin/tenants} page (Phase 3.2). It mirrors
 * {@link TenantAdminController} (the Thymeleaf onboarding UI) but speaks DTOs over
 * {@code /api/admin/tenants} and is secured by the stateless resource-server chain (bearer JWT).
 * The two controllers run side by side during the migration; this one collapses to the only one
 * when Thymeleaf is removed.
 *
 * <p>This is platform administration, restricted to SUPER_ADMIN. The {@code /api/admin/tenants/**}
 * matcher in {@code SecurityConfig}'s @Order(1) chain already enforces that role (mirroring the UI
 * chain's {@code /admin/tenants/**} rule), so no security change is needed here — unlike a normal
 * tenant-scoped page, the boundary is the matcher, not service scoping.
 *
 * <p>Onboarding writes the {@code tenant} row plus the first operator in Keycloak inside one
 * transaction ({@link TenantManagementService#createTenant}); a validation failure (blank/duplicate
 * name, taken email, short password) surfaces as {@link IllegalArgumentException} → {@code 400} with
 * the message, exactly the text the Thymeleaf page flashed. Create returns the refreshed tenant list
 * so the SPA re-renders without a second round trip.
 */
@RestController
@RequestMapping("/api/admin/tenants")
public class TenantAdminApiController {

    private static final Logger log = LoggerFactory.getLogger(TenantAdminApiController.class);

    private final TenantManagementService tenantManagementService;

    public TenantAdminApiController(TenantManagementService tenantManagementService) {
        this.tenantManagementService = tenantManagementService;
    }

    /** New-tenant payload: organisation name plus the first user's (the tenant admin's) login and name. */
    public record CreateTenantRequest(String name, String adminEmail, String adminFirstName,
                                      String adminLastName, String adminPassword) {}

    /** GET /api/admin/tenants — all tenants with their Keycloak operator counts (cross-tenant). */
    @GetMapping
    public List<TenantSummaryDto> list() {
        return snapshot();
    }

    /**
     * POST /api/admin/tenants — onboards a tenant and its first operator. The service owns the
     * validation; a rejected value answers {@code 400} with the message. Returns the refreshed list.
     */
    @PostMapping
    public List<TenantSummaryDto> create(@RequestBody CreateTenantRequest request) {
        try {
            Tenant tenant = tenantManagementService.createTenant(request.name(), request.adminEmail(),
                    OperatorName.of(request.adminFirstName(), request.adminLastName()), request.adminPassword());
            log.info("Tenant created: id={} name={}", tenant.getId(), tenant.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return snapshot();
    }

    /** Snapshots the platform tenant overview into DTOs (the entity never crosses the wire). */
    private List<TenantSummaryDto> snapshot() {
        return tenantManagementService.overview().stream().map(TenantSummaryDto::from).toList();
    }
}
