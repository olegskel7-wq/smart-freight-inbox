package com.smartfreight.inbox.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

/**
 * Spring Security configuration.
 *
 * <p><b>Two filter chains coexist (Phase 3.2 — React migration):</b>
 * <ol>
 *   <li><b>{@code /api/**}</b> — a stateless OAuth2 <i>resource server</i>. The React SPA obtains a
 *       Keycloak access token (authorization-code + PKCE, public client {@code smartfreight-spa})
 *       and calls the JSON API with a bearer token; this chain validates the JWT against the realm
 *       JWKS and maps the {@code roles} claim to authorities. No session, no CSRF (bearer tokens).</li>
 *   <li><b>everything else</b> — the original Keycloak <i>OIDC login</i> ({@code oauth2Login}) with a
 *       Redis-backed session, serving the Thymeleaf pages unchanged. The static SPA shell
 *       ({@code /app/**}) is {@code permitAll} — it is just files; real data is protected at
 *       {@code /api}. CSRF stays enabled for the Thymeleaf forms.</li>
 * </ol>
 * Both chains grant the SAME authorities for a user (see {@link KeycloakJwtRoleConverter} /
 * {@link TenantOidcUserService}); access rules are mirrored so the API enforces the same roles as
 * the UI. As pages migrate to React the Thymeleaf chain shrinks and is eventually dropped.
 *
 * <p>The principal is a {@link com.smartfreight.inbox.tenancy.TenantOidcUser} (login chain) or a
 * {@code Jwt} (API chain); {@link com.smartfreight.inbox.tenancy.TenantFilter} reads the tenant off
 * either. Access rules: {@code /actuator/**} public, {@code /admin/tenants/**} SUPER_ADMIN,
 * {@code /admin/**} OPERATOR, the rest authenticated.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class SecurityConfig {

    /**
     * Stateless resource-server chain for the SPA's JSON API. Ordered first so its
     * {@code securityMatcher("/api/**")} wins before the catch-all login chain below.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**")
            .authorizeHttpRequests(auth -> auth
                // Mirror the UI chain's rules. Matcher order matters: tenants before the broader /admin.
                .requestMatchers("/api/admin/tenants/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/admin/**").hasRole("OPERATOR")
                .anyRequest().authenticated()
            )
            // Bearer JWTs from Keycloak; the converter maps the realm roles claim to ROLE_* authorities.
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            // No server-side session for the API; identity rides in the token on every request.
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // CSRF protects session-cookie forms; a bearer-token API is not cookie-authenticated.
            .csrf(csrf -> csrf.disable());

        return http.build();
    }

    /**
     * Default OIDC-login chain (the pre-3.2 behaviour) for the Thymeleaf UI. Matches every request
     * not claimed by the API chain. The static SPA shell is public; its data calls go through /api.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http,
                                                      TenantOidcUserService tenantOidcUserService,
                                                      ClientRegistrationRepository clientRegistrationRepository)
            throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                // Static React bundle (Vite base "/app/"): HTML + assets. Data is protected at /api.
                .requestMatchers("/app/**").permitAll()
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
     * Converts a validated Keycloak JWT into an authentication carrying ROLE_* authorities derived
     * from the realm {@code roles} claim (see {@link KeycloakJwtRoleConverter}).
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakJwtRoleConverter());
        return converter;
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
