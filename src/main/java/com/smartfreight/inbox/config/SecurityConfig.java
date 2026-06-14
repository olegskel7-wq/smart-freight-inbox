package com.smartfreight.inbox.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

/**
 * Spring Security configuration (Phase 2.3 — Keycloak OIDC).
 *
 * Login is delegated entirely to Keycloak via the OIDC authorization-code flow
 * ({@code oauth2Login}). There is a single registered client, so Spring redirects any
 * unauthenticated request straight to Keycloak — no local login page. The session is still
 * stored server-side (JSESSIONID cookie, Redis-backed, Phase 2.6); brute-force protection now
 * lives in Keycloak (realm setting), replacing the Phase 2.6 login throttle.
 *
 * Access rules (unchanged from form login):
 * — {@code /actuator/**} — public (health-check for the load balancer and Prometheus)
 * — {@code /admin/tenants/**} — SUPER_ADMIN (platform tenant administration)
 * — {@code /admin/**} — OPERATOR
 * — everything else — authenticated
 *
 * The principal is a {@link com.smartfreight.inbox.tenancy.TenantOidcUser} built by
 * {@link TenantOidcUserService} from the token's {@code tenant_id} and {@code roles} claims.
 * CSRF stays enabled — Thymeleaf forms with {@code th:action} receive a token via Boot's
 * RequestDataValueProcessor.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   TenantOidcUserService tenantOidcUserService,
                                                   ClientRegistrationRepository clientRegistrationRepository)
            throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                // Tenant administration is platform-level (Phase 2.2 S2). Matcher order matters:
                // this rule must precede the broader /admin/** one.
                .requestMatchers("/admin/tenants/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/admin/**").hasRole("OPERATOR")
                .anyRequest().authenticated()
            )
            // OIDC login against Keycloak; the custom service maps tenant_id + roles claims.
            .oauth2Login(oauth -> oauth
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(tenantOidcUserService))
            )
            // RP-initiated logout: end the Keycloak SSO session too, not just the local one.
            .logout(logout -> logout
                .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository))
            );

        return http.build();
    }

    /**
     * RP-initiated logout handler. After clearing the local session it redirects the browser
     * to Keycloak's end-session endpoint, then back to the app root — where Spring, seeing no
     * authentication, sends the user to the Keycloak login page.
     */
    private LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository repo) {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(repo);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }
}
