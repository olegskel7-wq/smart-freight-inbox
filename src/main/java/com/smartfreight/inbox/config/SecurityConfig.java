package com.smartfreight.inbox.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration.
 *
 * <p><b>Two filter chains coexist (post Phase 3.2 cutover — the app is now a React SPA):</b>
 * <ol>
 *   <li><b>{@code /api/**}</b> — a stateless OAuth2 <i>resource server</i>. The React SPA obtains a
 *       Keycloak access token (authorization-code + PKCE, public client {@code smartfreight-spa})
 *       and calls the JSON API with a bearer token; this chain validates the JWT against the realm
 *       JWKS and maps the {@code roles} claim to authorities. No session, no CSRF (bearer tokens).</li>
 *   <li><b>everything else</b> — the public static surface: the SPA shell + assets (served from
 *       {@code classpath:/static/app/} by {@link SpaWebConfig}), {@code /actuator/**} and the STOMP
 *       {@code /ws} handshake. These carry no secrets — every piece of real data is behind
 *       {@code /api} (chain 1) and the WebSocket is authenticated on its CONNECT frame
 *       ({@code StompAuthChannelInterceptor}). The browser performs OIDC login directly against
 *       Keycloak (PKCE), so there is no server-side {@code oauth2Login} flow anymore.</li>
 * </ol>
 *
 * <p>The API principal is a {@code Jwt}; {@link com.smartfreight.inbox.tenancy.TenantFilter} reads
 * the tenant off the {@code tenant_id} claim. Access rules (API chain): {@code /api/admin/tenants/**}
 * SUPER_ADMIN; {@code /api/admin/**} (reprocess + DLQ maintenance), {@code /api/users/**} and
 * {@code /api/settings/**} ADMIN (Phase 3.3 tenant-admin tier); the rest authenticated (OPERATOR).
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
                // Matcher order matters: most specific first. Tenant onboarding is platform-level
                // (SUPER_ADMIN); team/settings/platform-maintenance are tenant-admin surfaces
                // (ADMIN — Phase 3.3); everything else (tickets/analytics/rates/documents/learning)
                // is day-to-day operator work, so it only needs an authenticated OPERATOR.
                .requestMatchers("/api/admin/tenants/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                .requestMatchers("/api/settings/**").hasRole("ADMIN")
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
     * Public static chain for everything not claimed by the API chain: the SPA shell + assets,
     * the actuator endpoints and the STOMP {@code /ws} handshake. None of it is sensitive — the
     * SPA is just files, real data is enforced at {@code /api} (chain 1), and the WebSocket
     * authenticates its CONNECT frame in {@code StompAuthChannelInterceptor}. There is no
     * server-side login: the browser does PKCE directly against Keycloak and logs out the same way.
     * Stateless and CSRF-off because nothing here is cookie-authenticated.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable());

        return http.build();
    }

    /**
     * The single {@link JwtDecoder} backing both the {@code /api} resource server and
     * {@code StompAuthChannelInterceptor}. Built to match Boot's auto-config (keys from
     * {@code jwk-set-uri}, issuer pinned to {@code issuer-uri}, no discovery call at startup so the
     * context boots without a live Keycloak), then hardened with {@link ExpectedClientJwtValidator}:
     * on top of issuer + signature we require the token's {@code azp} to be our own SPA client, so a
     * token minted for any other client in the realm is rejected (ROADMAP 4.7). The JWKS is fetched
     * lazily on first decode, so defining this bean keeps the existing startup behaviour.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${security.jwt.expected-client:smartfreight-spa}") String expectedClient) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new ExpectedClientJwtValidator(expectedClient));
        decoder.setJwtValidator(validators);
        return decoder;
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
}
