package com.smartfreight.inbox.config;

import com.smartfreight.inbox.service.LoginRateLimiter;
import com.smartfreight.inbox.web.LoginRateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   LoginRateLimitFilter loginRateLimitFilter,
                                                   LoginRateLimiter loginRateLimiter) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                // Tenant administration is platform-level (Phase 2.2 S2) — a tenant's own
                // operators must never see other tenants. Matcher order matters: this
                // rule must precede the broader /admin/** one.
                .requestMatchers("/admin/tenants/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/admin/**").hasRole("OPERATOR")
                .anyRequest().authenticated()
            )
            // Brute-force throttle (Phase 2.6): reject over-limit logins before authentication.
            .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("email")
                .successHandler(loginSuccessHandler(loginRateLimiter))
                .failureHandler(loginFailureHandler(loginRateLimiter))
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout=true")
                .permitAll()
            )
            .sessionManagement(session -> session
                .invalidSessionUrl("/login?expired=true")
                .maximumSessions(5)
            );

        return http.build();
    }

    /**
     * On success: clear the account's failure counter (Phase 2.6) and redirect to the tickets
     * page — preserving the previous {@code defaultSuccessUrl("/tickets", true)} behaviour.
     */
    private AuthenticationSuccessHandler loginSuccessHandler(LoginRateLimiter loginRateLimiter) {
        return (request, response, authentication) -> {
            loginRateLimiter.reset(authentication.getName());
            response.sendRedirect(request.getContextPath() + "/tickets");
        };
    }

    /**
     * On failure: record the failed attempt against the IP and email counters (Phase 2.6),
     * then fall back to the standard {@code /login?error=true} redirect.
     */
    private AuthenticationFailureHandler loginFailureHandler(LoginRateLimiter loginRateLimiter) {
        return (request, response, exception) -> {
            loginRateLimiter.recordFailure(request.getRemoteAddr(), request.getParameter("email"));
            response.sendRedirect(request.getContextPath() + "/login?error=true");
        };
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
