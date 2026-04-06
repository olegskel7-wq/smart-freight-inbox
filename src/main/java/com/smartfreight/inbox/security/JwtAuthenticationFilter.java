package com.smartfreight.inbox.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

// @Component — Spring создаёт этот фильтр как бин и регистрирует в цепочке фильтров
@Component
// OncePerRequestFilter — гарантирует, что фильтр вызывается ровно ОДИН раз на запрос
// (некоторые фильтры могут вызваться дважды при редиректе — этот нет)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    // JwtService — утилита для работы с JWT токенами (проверка, извлечение данных)
    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    // Этот метод вызывается при каждом входящем HTTP-запросе к нашему API
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,   // входящий HTTP-запрос
            @NonNull HttpServletResponse response, // исходящий HTTP-ответ
            @NonNull FilterChain filterChain       // оставшиеся фильтры в цепочке
    ) throws ServletException, IOException {

        // Читаем заголовок "Authorization" из запроса.
        // Клиент обязан передавать: Authorization: Bearer eyJhbGci...
        final String authHeader = request.getHeader("Authorization");

        // Если заголовка нет или он не начинается с "Bearer " —
        // пропускаем запрос дальше (Security сам решит — 401 или нет)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Извлекаем сам токен: отрезаем "Bearer " (7 символов) в начале
        // "Bearer eyJhbGci..." → "eyJhbGci..."
        final String jwt = authHeader.substring(7);

        try {
            // Извлекаем email пользователя из payload'а JWT
            final String userEmail = jwtService.extractUsername(jwt);

            // Если email извлечён И пользователь ещё не аутентифицирован в этом запросе
            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // Проверяем: токен не истёк и его подпись верна
                if (jwtService.isTokenValid(jwt, userEmail)) {

                    // Создаём объект аутентификации для Spring Security
                    // Параметры: (principal=email, credentials=null, authorities=пустой список)
                    var authToken = new UsernamePasswordAuthenticationToken(
                            userEmail, null, Collections.emptyList()
                    );

                    // Добавляем детали запроса (IP-адрес, ID сессии) к токену аутентификации
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // Сохраняем аутентификацию в SecurityContext —
                    // Spring Security теперь знает, что запрос от авторизованного пользователя
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            // Токен невалиден (истёк, подделан, неверная подпись) —
            // просто логируем и продолжаем без аутентификации.
            // Spring Security сам вернёт 401 для защищённых эндпоинтов.
            log.warn("JWT validation failed: {}", e.getMessage());
        }

        // Передаём запрос следующему фильтру в цепочке (или контроллеру)
        filterChain.doFilter(request, response);
    }
}
