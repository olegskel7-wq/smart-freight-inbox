package com.smartfreight.port_service.config;

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
 * – {@code /actuator/**} – open (health-check, Prometheus scraping)
 * – {@code /api/containers/**} – requires a valid {@code X-API-Key} header
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
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
            );

        return http.build();
    }
}
