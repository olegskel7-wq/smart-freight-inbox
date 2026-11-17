package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantManagementServiceTest {

    private static final OperatorName DANA = new OperatorName("Dana", "Price");

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private KeycloakAdminService keycloakAdminService;

    @InjectMocks
    private TenantManagementService service;

    @BeforeEach
    void stubSave() {
        lenient().when(tenantRepository.save(any())).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(42L);
            return t;
        });
    }

    @Test
    void createTenant_createsTenantRowAndFirstOperatorInKeycloak() {
        when(tenantRepository.findByName("Acme Logistics")).thenReturn(Optional.empty());
        when(keycloakAdminService.emailExists("ops@acme.co.uk")).thenReturn(false);

        Tenant created = service.createTenant("  Acme Logistics  ", "  OPS@Acme.co.uk ", DANA, "secret-pass");

        assertThat(created.getName()).isEqualTo("Acme Logistics");
        // The first user goes into Keycloak (email normalised) scoped to the new tenant id, as its
        // ADMIN — an OPERATOR could not reach /settings to connect the tenant's mailbox (Gate B6).
        verify(keycloakAdminService).createUser("ops@acme.co.uk", DANA, "secret-pass", 42L, "ADMIN");
    }

    @Test
    void createTenant_passwordRejectedByRealmPolicy_isAClear400NotA500() {
        when(tenantRepository.findByName("Acme")).thenReturn(Optional.empty());
        when(keycloakAdminService.emailExists("ops@acme.co.uk")).thenReturn(false);
        when(keycloakAdminService.createUser(any(), any(), any(), any(), any())).thenThrow(
                WebClientResponseException.create(400, "Bad Request", null,
                        "{\"errorMessage\":\"invalidPasswordMinUpperCaseCharsMessage\"}".getBytes(), null));

        // 10+ chars passes our length check; Keycloak's upper/lower/digit policy rejects it.
        assertThatThrownBy(() -> service.createTenant("Acme", "ops@acme.co.uk", DANA, "alllowercase"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(UserManagementService.PASSWORD_POLICY_MESSAGE);
    }

    @Test
    void createTenant_rejectsDuplicateName() {
        when(tenantRepository.findByName("Acme")).thenReturn(Optional.of(new Tenant()));

        assertThatThrownBy(() -> service.createTenant("Acme", "ops@acme.co.uk", DANA, "secret-pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        verify(tenantRepository, never()).save(any());
        verify(keycloakAdminService, never()).createUser(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void createTenant_rejectsBlankName() {
        assertThatThrownBy(() -> service.createTenant("   ", "ops@acme.co.uk", DANA, "secret-pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void createTenant_rejectsShortPassword() {
        when(tenantRepository.findByName("Acme")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createTenant("Acme", "ops@acme.co.uk", DANA, "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 10");
        verify(tenantRepository, never()).save(any());
        verify(keycloakAdminService, never()).createUser(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void createTenant_rejectsTakenEmail() {
        when(tenantRepository.findByName("Acme")).thenReturn(Optional.empty());
        when(keycloakAdminService.emailExists("ops@acme.co.uk")).thenReturn(true);

        assertThatThrownBy(() -> service.createTenant("Acme", "ops@acme.co.uk", DANA, "secret-pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        verify(tenantRepository, never()).save(any());
        verify(keycloakAdminService, never()).createUser(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void overview_pairsTenantsWithKeycloakUserCounts() {
        Tenant a = Tenant.builder().id(1L).name("Default").build();
        Tenant b = Tenant.builder().id(2L).name("Acme").build();
        when(tenantRepository.findAll()).thenReturn(List.of(b, a));
        when(keycloakAdminService.countUsersByTenant(1L)).thenReturn(3L);
        when(keycloakAdminService.countUsersByTenant(2L)).thenReturn(1L);

        List<TenantManagementService.TenantSummary> overview = service.overview();

        assertThat(overview).extracting(s -> s.tenant().getName()).containsExactly("Default", "Acme");
        assertThat(overview).extracting(TenantManagementService.TenantSummary::userCount).containsExactly(3L, 1L);
    }
}
