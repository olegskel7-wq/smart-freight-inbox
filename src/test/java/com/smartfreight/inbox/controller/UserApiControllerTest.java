package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.service.KeycloakAdminService.KeycloakUser;
import com.smartfreight.inbox.service.UserManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for the JSON users API (Phase 3.2). No security/tenant filter here
 * (covered by KeycloakJwtRoleConverterTest / TenantFilterTest); this verifies the DTO snapshot
 * (no tenant id leaks), the create/password/delete flows, validation → 400, and that delete reads
 * the current operator's email off the JWT for the self-delete guard.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserApiControllerTest {

    @Mock private UserManagementService userManagementService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(userManagementService.findAll()).thenReturn(List.of(
                new KeycloakUser("uuid-1", "alice@sf.com", "OPERATOR", 1L)));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new UserApiController(userManagementService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Puts a Keycloak JWT (with preferred_username) in the context so @AuthenticationPrincipal resolves. */
    private void authenticateAs(String email) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("preferred_username", email).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void listReturnsTenantOperatorsWithoutTenantId() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("uuid-1"))
                .andExpect(jsonPath("$[0].email").value("alice@sf.com"))
                .andExpect(jsonPath("$[0].role").value("OPERATOR"))
                .andExpect(jsonPath("$[0].tenantId").doesNotExist());
    }

    @Test
    void createDefaultsRoleAndReturnsSnapshot() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@sf.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("uuid-1"));

        verify(userManagementService).createUser(eq("new@sf.com"), eq("password123"), eq("OPERATOR"));
    }

    @Test
    void createBlankEmailIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"  \",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest());

        verify(userManagementService, never()).createUser(eq("  "), eq("password123"), eq("OPERATOR"));
    }

    @Test
    void createPropagatesValidationErrorAsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("User with email new@sf.com already exists."))
                .when(userManagementService).createUser(eq("new@sf.com"), eq("short"), eq("OPERATOR"));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@sf.com\",\"password\":\"short\",\"role\":\"OPERATOR\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePasswordReturnsSnapshot() throws Exception {
        mockMvc.perform(post("/api/users/uuid-1/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"newpassword1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("uuid-1"));

        verify(userManagementService).changePassword(eq("uuid-1"), eq("newpassword1"));
    }

    @Test
    void deletePassesCurrentEmailFromJwt() throws Exception {
        authenticateAs("admin@sf.com");

        mockMvc.perform(delete("/api/users/uuid-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("uuid-1"));

        verify(userManagementService).deleteUser(eq("uuid-2"), eq("admin@sf.com"));
    }

    @Test
    void deleteSelfIsBadRequest() throws Exception {
        authenticateAs("alice@sf.com");
        doThrow(new IllegalArgumentException("You cannot delete your own account."))
                .when(userManagementService).deleteUser(eq("uuid-1"), eq("alice@sf.com"));

        mockMvc.perform(delete("/api/users/uuid-1"))
                .andExpect(status().isBadRequest());
    }
}
