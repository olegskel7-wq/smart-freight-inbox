package com.smartfreight.inbox.service;

import com.smartfreight.inbox.service.KeycloakAdminService.KeycloakUser;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    private static final OperatorName DANA = new OperatorName("Dana", "Price");

    private static final long TENANT = 1L;

    @Mock
    private KeycloakAdminService keycloakAdminService;

    @InjectMocks
    private UserManagementService service;

    @Test
    void findAll_listsCurrentTenantOperators() {
        KeycloakUser u = new KeycloakUser("id-1", "a@acme.co", "OPERATOR", TENANT);
        when(keycloakAdminService.listUsersByTenant(TENANT)).thenReturn(List.of(u));

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThat(service.findAll()).containsExactly(u);
        }
    }

    @Test
    void createUser_createsInKeycloakScopedToTenantWithNormalisedEmail() {
        when(keycloakAdminService.emailExists("new@acme.co")).thenReturn(false);

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            service.createUser("  NEW@Acme.co ", DANA, "secret-pass", "OPERATOR");
        }

        verify(keycloakAdminService).createUser("new@acme.co", DANA, "secret-pass", TENANT, "OPERATOR");
    }

    @Test
    void createUser_rejectsShortPassword() {
        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThatThrownBy(() -> service.createUser("new@acme.co", DANA, "short", "OPERATOR"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 10");
        }
        verify(keycloakAdminService, never()).createUser(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void createUser_translatesKeycloakPolicyRejectionToBadRequest() {
        when(keycloakAdminService.emailExists("new@acme.co")).thenReturn(false);
        when(keycloakAdminService.createUser(anyString(), any(), anyString(), any(), anyString()))
                .thenThrow(WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY,
                        "{\"errorMessage\":\"invalidPasswordMinUpperCaseCharsMessage\"}".getBytes(), null));

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThatThrownBy(() -> service.createUser("new@acme.co", DANA, "weakpasswordnouppercase", "OPERATOR"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("security policy");
        }
    }

    @Test
    void createUser_keycloakNameRejection_isNotReportedAsAPasswordProblem() {
        when(keycloakAdminService.emailExists("new@acme.co")).thenReturn(false);
        when(keycloakAdminService.createUser(anyString(), any(), anyString(), any(), anyString()))
                .thenThrow(WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY,
                        "{\"field\":\"firstName\",\"errorMessage\":\"error-person-name-invalid-character\"}".getBytes(),
                        null));

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThatThrownBy(() -> service.createUser("new@acme.co", new OperatorName("<b>", "Price"),
                    "secret-pass", "OPERATOR"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name contains characters")
                    .hasMessageNotContaining("security policy");
        }
    }

    @Test
    void operatorName_requiresBothParts_andTrims() {
        assertThat(OperatorName.of("  Dana ", " Price  ")).isEqualTo(DANA);
        assertThatThrownBy(() -> OperatorName.of("Dana", "  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required");
        assertThatThrownBy(() -> OperatorName.of(null, "Price"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required");
        assertThatThrownBy(() -> OperatorName.of("x".repeat(101), "Price"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 100");
    }

    @Test
    void changePassword_translatesKeycloakPolicyRejectionToBadRequest() {
        KeycloakUser u = new KeycloakUser("id-9", "op@acme.co", "OPERATOR", TENANT);
        when(keycloakAdminService.findUser("id-9")).thenReturn(Optional.of(u));
        doThrow(WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY, new byte[0], null))
                .when(keycloakAdminService).resetPassword(anyString(), anyString());

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThatThrownBy(() -> service.changePassword("id-9", "weakpasswordnouppercase"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("security policy");
        }
    }

    @Test
    void createUser_rejectsTakenEmail() {
        when(keycloakAdminService.emailExists("taken@acme.co")).thenReturn(true);

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThatThrownBy(() -> service.createUser("taken@acme.co", DANA, "secret-pass", "OPERATOR"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
        }
        verify(keycloakAdminService, never()).createUser(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void createUser_rejectsNonAssignableRole() {
        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThatThrownBy(() -> service.createUser("new@acme.co", DANA, "secret-pass", "SUPER_ADMIN"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid role");
        }
        verify(keycloakAdminService, never()).createUser(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void changeRole_swapsRoleForOwnTenantUser() {
        KeycloakUser u = new KeycloakUser("id-7", "op@acme.co", "OPERATOR", TENANT);
        when(keycloakAdminService.findUser("id-7")).thenReturn(Optional.of(u));

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            service.changeRole("id-7", "ADMIN", "me@acme.co");
        }

        verify(keycloakAdminService).setRealmRole("id-7", "ADMIN");
    }

    @Test
    void changeRole_rejectsInvalidRole() {
        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThatThrownBy(() -> service.changeRole("id-7", "SUPER_ADMIN", "me@acme.co"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid role");
        }
        verify(keycloakAdminService, never()).setRealmRole(anyString(), anyString());
    }

    @Test
    void changeRole_preventsSelfRoleChange() {
        KeycloakUser me = new KeycloakUser("id-7", "me@acme.co", "ADMIN", TENANT);
        when(keycloakAdminService.findUser("id-7")).thenReturn(Optional.of(me));

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThatThrownBy(() -> service.changeRole("id-7", "OPERATOR", "ME@acme.co"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("your own role");
        }
        verify(keycloakAdminService, never()).setRealmRole(anyString(), anyString());
    }

    @Test
    void changeRole_rejectsCrossTenantUser() {
        KeycloakUser other = new KeycloakUser("id-7", "op@other.co", "OPERATOR", 2L);
        when(keycloakAdminService.findUser("id-7")).thenReturn(Optional.of(other));

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThatThrownBy(() -> service.changeRole("id-7", "ADMIN", "me@acme.co"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }
        verify(keycloakAdminService, never()).setRealmRole(anyString(), anyString());
    }

    @Test
    void changePassword_resetsForOwnTenantUser() {
        KeycloakUser u = new KeycloakUser("id-9", "op@acme.co", "OPERATOR", TENANT);
        when(keycloakAdminService.findUser("id-9")).thenReturn(Optional.of(u));

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            service.changePassword("id-9", "new-secret");
        }

        verify(keycloakAdminService).resetPassword("id-9", "new-secret");
    }

    @Test
    void changePassword_rejectsCrossTenantUser() {
        KeycloakUser other = new KeycloakUser("id-9", "op@other.co", "OPERATOR", 2L);
        when(keycloakAdminService.findUser("id-9")).thenReturn(Optional.of(other));

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThatThrownBy(() -> service.changePassword("id-9", "new-secret"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }
        verify(keycloakAdminService, never()).resetPassword(anyString(), anyString());
    }

    @Test
    void deleteUser_deletesOtherUserInTenant() {
        KeycloakUser u = new KeycloakUser("id-3", "other@acme.co", "OPERATOR", TENANT);
        when(keycloakAdminService.findUser("id-3")).thenReturn(Optional.of(u));

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            service.deleteUser("id-3", "me@acme.co");
        }

        verify(keycloakAdminService).deleteUser("id-3");
    }

    @Test
    void deleteUser_preventsSelfDelete() {
        KeycloakUser me = new KeycloakUser("id-3", "me@acme.co", "OPERATOR", TENANT);
        when(keycloakAdminService.findUser("id-3")).thenReturn(Optional.of(me));

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThatThrownBy(() -> service.deleteUser("id-3", "ME@acme.co"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("your own account");
        }
        verify(keycloakAdminService, never()).deleteUser(anyString());
    }

    @Test
    void deleteUser_rejectsUnknownUser() {
        when(keycloakAdminService.findUser("ghost")).thenReturn(Optional.empty());

        try (TenantContext.Scope ignored = TenantContext.openAs(TENANT)) {
            assertThatThrownBy(() -> service.deleteUser("ghost", "me@acme.co"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }
        verify(keycloakAdminService, never()).deleteUser(anyString());
    }
}
