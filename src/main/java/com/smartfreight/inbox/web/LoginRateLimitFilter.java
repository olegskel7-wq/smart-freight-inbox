package com.smartfreight.inbox.web;

import com.smartfreight.inbox.service.LoginRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects {@code POST /login} attempts from an IP or against an account that has exceeded the
 * failure threshold (Phase 2.6), before Spring Security authenticates them. A blocked attempt
 * is bounced to {@code /login?blocked=true} without touching the password hash or the database.
 *
 * <p>Registered ahead of {@code UsernamePasswordAuthenticationFilter} in {@link
 * com.smartfreight.inbox.config.SecurityConfig}. Only the login POST is inspected; every other
 * request passes straight through.
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/login";

    private final LoginRateLimiter limiter;

    public LoginRateLimitFilter(LoginRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && LOGIN_PATH.equals(request.getServletPath())) {
            String ip = request.getRemoteAddr();
            String email = request.getParameter("email");
            if (limiter.isBlocked(ip, email)) {
                response.sendRedirect(request.getContextPath() + "/login?blocked=true");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
