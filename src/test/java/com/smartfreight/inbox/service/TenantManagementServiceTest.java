package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.User;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantManagementServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private TenantManagementService service;

    @BeforeEach
    void stubSaves() {
        lenient().when(tenantRepository.save(any())).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(42L);
            return t;
        });
        lenient().when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hash");
    }

    @Test
    void createTenant_createsTenantAndFirstOperatorAtomically() {
        when(tenantRepository.findByName("Acme Logistics")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ops@acme.co.uk")).thenReturn(Optional.empty());

        Tenant created = service.createTenant("  Acme Logistics  ", "  OPS@Acme.co.uk ", "secret-pass");

        assertThat(created.getName()).isEqualTo("Acme Logistics");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ops@acme.co.uk");
        assertThat(saved.getValue().getTenantId()).isEqualTo(42L);
        assertThat(saved.getValue().getRole()).isEqualTo("OPERATOR");
        assertThat(saved.getValue().getPassword()).isEqualTo("{bcrypt}hash");
    }

    @Test
    void createTenant_rejectsDuplicateName() {
        when(tenantRepository.findByName("Acme")).thenReturn(Optional.of(new Tenant()));

        assertThatThrownBy(() -> service.createTenant("Acme", "ops@acme.co.uk", "secret-pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void createTenant_rejectsBlankName() {
        assertThatThrownBy(() -> service.createTenant("   ", "ops@acme.co.uk", "secret-pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void createTenant_rejectsShortPassword() {
        when(tenantRepository.findByName("Acme")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createTenant("Acme", "ops@acme.co.uk", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 8");
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void createTenant_rejectsTakenEmail() {
        when(tenantRepository.findByName("Acme")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ops@acme.co.uk")).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> service.createTenant("Acme", "ops@acme.co.uk", "secret-pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void overview_pairsTenantsWithUserCounts() {
        Tenant a = Tenant.builder().id(1L).name("Default").build();
        Tenant b = Tenant.builder().id(2L).name("Acme").build();
        when(tenantRepository.findAll()).thenReturn(List.of(b, a));
        when(userRepository.countByTenantId(1L)).thenReturn(3L);
        when(userRepository.countByTenantId(2L)).thenReturn(1L);

        List<TenantManagementService.TenantSummary> overview = service.overview();

        assertThat(overview).extracting(s -> s.tenant().getName()).containsExactly("Default", "Acme");
        assertThat(overview).extracting(TenantManagementService.TenantSummary::userCount).containsExactly(3L, 1L);
    }
}
