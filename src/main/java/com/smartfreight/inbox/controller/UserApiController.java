package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.UserDto;
import com.smartfreight.inbox.service.OperatorName;
import com.smartfreight.inbox.service.UserManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * JSON API backing the React {@code /users} page (Phase 3.2). It mirrors
 * {@link UserManagementController} (the Thymeleaf operator-management UI) but speaks DTOs over
 * {@code /api/users} and is secured by the stateless resource-server chain (bearer JWT). The two
 * controllers run side by side during the migration; this one collapses to the only one when
 * Thymeleaf is removed.
 *
 * <p>Operators are Keycloak realm users (Phase 2.3); {@link UserManagementService} scopes every
 * call to the caller's tenant and owns the validation (password length, duplicate email, self-delete
 * guard) — a rejected value surfaces here as {@link IllegalArgumentException} → {@code 400} with the
 * message, exactly the text the Thymeleaf page showed as a flash error. Every mutation returns the
 * refreshed operator list so the SPA re-renders without a second round trip.
 *
 * <p>No {@code SecurityConfig} change: {@code /api/users/**} falls under the @Order(1) chain's
 * {@code anyRequest().authenticated()} — {@code /users} was never SUPER_ADMIN-gated (only
 * {@code /admin/tenants/**} is), and the service's per-tenant scoping is the real boundary.
 */
@RestController
@RequestMapping("/api/users")
public class UserApiController {

    private static final Logger log = LoggerFactory.getLogger(UserApiController.class);

    private final UserManagementService userManagementService;

    public UserApiController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    /** New-operator payload (role defaults to OPERATOR when blank; OPERATOR/ADMIN accepted, Phase 3.3). */
    public record CreateUserRequest(String email, String firstName, String lastName, String password, String role) {}

    /** Password-reset payload for an existing operator. */
    public record ChangePasswordRequest(String newPassword) {}

    /** Role-change payload (OPERATOR ↔ ADMIN, Phase 3.3). */
    public record ChangeRoleRequest(String role) {}

    /** GET /api/users — the caller's tenant operators. */
    @GetMapping
    public List<UserDto> list() {
        return snapshot();
    }

    /**
     * POST /api/users — creates an operator in the caller's tenant. A blank email answers {@code 400};
     * the service rejects a short password or a duplicate email the same way (→ {@code 400} + message).
     */
    @PostMapping
    public List<UserDto> create(@RequestBody CreateUserRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required.");
        }
        String role = (request.role() == null || request.role().isBlank()) ? "OPERATOR" : request.role();
        try {
            userManagementService.createUser(request.email(),
                    OperatorName.of(request.firstName(), request.lastName()), request.password(), role);
            log.info("User created: {}", request.email().trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return snapshot();
    }

    /**
     * POST /api/users/{id}/role — changes an operator's tenant role (OPERATOR ↔ ADMIN, Phase 3.3).
     * The caller's email comes off the JWT; the service refuses a self-role-change to avoid an admin
     * locking the tenant out of its own management surface. An invalid role / cross-tenant target /
     * self-target answers {@code 400} with the message.
     */
    @PostMapping("/{id}/role")
    public List<UserDto> changeRole(@PathVariable String id,
                                    @RequestBody ChangeRoleRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
        try {
            String currentEmail = jwt == null ? null : jwt.getClaimAsString("preferred_username");
            userManagementService.changeRole(id, request.role(), currentEmail);
            log.info("Role changed for user id={} to {} by {}", id, request.role(), currentEmail);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return snapshot();
    }

    /** POST /api/users/{id}/password — resets an operator's password (tenant-scoped in the service). */
    @PostMapping("/{id}/password")
    public List<UserDto> changePassword(@PathVariable String id,
                                        @RequestBody ChangePasswordRequest request) {
        try {
            userManagementService.changePassword(id, request.newPassword());
            log.info("Password changed for user id={}", id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return snapshot();
    }

    /**
     * DELETE /api/users/{id} — removes an operator. The current operator's email comes off the JWT
     * ({@code preferred_username}); the service refuses a self-delete to avoid accidental lockout.
     */
    @DeleteMapping("/{id}")
    public List<UserDto> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        try {
            String currentEmail = jwt == null ? null : jwt.getClaimAsString("preferred_username");
            userManagementService.deleteUser(id, currentEmail);
            log.info("User id={} deleted by {}", id, currentEmail);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return snapshot();
    }

    /** Snapshots the caller's tenant operators into DTOs (no tenant id leaks to the client). */
    private List<UserDto> snapshot() {
        return userManagementService.findAll().stream().map(UserDto::from).toList();
    }
}
