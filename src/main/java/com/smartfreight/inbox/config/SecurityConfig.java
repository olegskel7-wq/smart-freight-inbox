package com.smartfreight.inbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for application security.
 *
 * Uses form login — suitable for browser clients with the Thymeleaf UI.
 * The session is stored in a JSESSIONID cookie.
 *
 * Access rules:
 * — {@code /actuator/**} — public (health-check for load balancer and Prometheus)
 * — {@code /admin/**} — requires authentication + localhost check inside AdminController
 * — everything else — requires authentication
 *
 * CSRF: enabled — Thymeleaf forms with {@code th:action} automatically receive a CSRF token.
 *
 * User source — {@code app_user} table in the DB via {@link DbUserDetailsService}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Security filter chain — the main authorisation configuration.
     *
     * Form login (for browser clients, Thymeleaf UI):
     * — {@code /login} — login form page
     * — username parameter: {@code "email"} (not the standard "username")
     * — on success redirect to {@code /tickets}
     * — on error → {@code /login?error=true}
     * — on logout → {@code /login?logout=true}
     *
     * @param http HttpSecurity builder
     * @return configured security filter chain
     * @throws Exception on configuration error
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/admin/**").hasRole("OPERATOR")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("email")
                .defaultSuccessUrl("/tickets", true)
                .failureUrl("/login?error=true")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout=true")
                .permitAll()
            );

        return http.build();
    }

    /**
     * BCrypt password encoder with an automatically generated salt.
     *
     * Picked up automatically by {@code DaoAuthenticationProvider}
     * via {@link DbUserDetailsService} for login verification.
     *
     * @return configured BCryptPasswordEncoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
