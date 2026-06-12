package com.smartfreight.inbox.config;

import com.smartfreight.inbox.entity.User;
import com.smartfreight.inbox.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DbUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DbUserDetailsService service;

    private User user(String role) {
        return User.builder()
                .id(1L)
                .email("user@test.com")
                .password("{bcrypt}hash")
                .role(role)
                .tenantId(1L)
                .build();
    }

    @Test
    void superAdmin_keepsOperatorAccess() {
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user("SUPER_ADMIN")));

        UserDetails details = service.loadUserByUsername("user@test.com");

        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_SUPER_ADMIN", "ROLE_OPERATOR");
    }

    @Test
    void regularRole_getsSingleAuthority() {
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user("OPERATOR")));

        assertThat(service.loadUserByUsername("user@test.com").getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_OPERATOR");
    }

    @Test
    void nullRole_defaultsToOperator() {
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user(null)));

        assertThat(service.loadUserByUsername("user@test.com").getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_OPERATOR");
    }

    @Test
    void unknownUser_throwsWithoutLeakingDetail() {
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost@test.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
