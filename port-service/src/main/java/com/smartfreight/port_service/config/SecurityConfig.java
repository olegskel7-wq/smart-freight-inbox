package com.smartfreight.port_service.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for securing the port-service REST API.
 *
 * A simple API key mechanism is used instead of full OAuth2/JWT,
 * because the only client is the internal inbox service within the same network environment.
 *
 * Access rules:
 * – {@code /actuator/health/**} – open (Kubernetes/Docker liveness & readiness probes)
 * – {@code /actuator/prometheus} – open (Prometheus scrape target; Prometheus cannot send a
 *   custom X-API-Key header, so this low-sensitivity metrics endpoint stays reachable and must
 *   be protected by network isolation in production, not by the application key)
 * – {@code /actuator/**} (metrics, ...) – requires a valid {@code X-API-Key} header,
 *   so the rest of the management surface is never exposed to anonymous callers
 * – {@code /api/v1/containers/**} – requires a valid {@code X-API-Key} header
 *
 * Note: {@code management.endpoint.health.show-details} is {@code when-authorized}, so anonymous
 * health probes only see UP/DOWN — never DB versions, pool state, or disk details.
 *
 * The API key is configured via {@code port.api.key} (environment variable PORT_SERVICE_API_KEY).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${port.api.key}")
    private String apiKey;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new ApiKeyFilter(apiKey),
                UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/prometheus").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, authEx) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
            );

        return http.build();
    }
}
