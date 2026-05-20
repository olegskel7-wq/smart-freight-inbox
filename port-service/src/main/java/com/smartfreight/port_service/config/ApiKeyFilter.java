package com.smartfreight.port_service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Filter for API key authentication via the {@code X-API-Key} HTTP header.
 *
 * Validates every request: if the header matches the configured key,
 * the request is authenticated and passed down the chain. Otherwise,
 * {@link SecurityConfig} denies access (HTTP 401).
 *
 * Usage: the inbox service sends {@code X-API-Key: <key>} with every
 * GET /api/containers/{number} request via {@code portServiceWebClient}.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-API-Key";

    private final String validApiKey;

    public ApiKeyFilter(String validApiKey) {
        this.validApiKey = validApiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String providedKey = request.getHeader(API_KEY_HEADER);
        if (providedKey != null && MessageDigest.isEqual(
                validApiKey.getBytes(StandardCharsets.UTF_8),
                providedKey.getBytes(StandardCharsets.UTF_8))) {
            var auth = new UsernamePasswordAuthenticationToken(
                    "inbox-service", null,
                    List.of(new SimpleGrantedAuthority("ROLE_API"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }
}
