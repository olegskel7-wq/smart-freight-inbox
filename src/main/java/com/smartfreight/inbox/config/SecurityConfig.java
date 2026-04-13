package com.smartfreight.inbox.config;

import com.smartfreight.inbox.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// @Configuration — этот класс содержит настройки Spring
@Configuration
// @EnableWebSecurity — активирует Spring Security для всего приложения
@EnableWebSecurity
public class SecurityConfig {

    // Наш кастомный JWT фильтр (проверяет токен из заголовка запроса)
    private final JwtAuthenticationFilter jwtAuthFilter;

    // Spring автоматически передаёт JwtAuthenticationFilter через конструктор
    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    // SecurityFilterChain — цепочка правил: что разрешено, что нет
    // и как проходить аутентификацию. Spring Security применяет их к каждому HTTP-запросу.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF (Cross-Site Request Forgery) — защита от браузерных атак.
            // Отключаем, потому что наш REST API использует JWT, а не cookies/сессии.
            // JWT уже защищает от CSRF — каждый запрос несёт токен в заголовке.
            .csrf(AbstractHttpConfigurer::disable)

            // Правила авторизации — кто имеет доступ к чему
            .authorizeHttpRequests(auth -> auth
                // REST API login — публичный эндпоинт (нужен чтобы получить JWT токен)
                .requestMatchers("/api/auth/**").permitAll()
                // /actuator/health — публичный (для мониторинга и Docker health check)
                .requestMatchers("/actuator/**").permitAll()
                // /admin/** — доступно только с localhost (фильтрация внутри AdminController)
                .requestMatchers("/admin/**").permitAll()
                // Страница логина Thymeleaf UI — доступна без авторизации
                .requestMatchers(HttpMethod.GET, "/", "/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/login").permitAll()
                // Всё остальное — только для аутентифицированных пользователей с JWT
                .anyRequest().authenticated()
            )

            // STATELESS — сервер не хранит сессии.
            // Каждый запрос самодостаточен: JWT передаётся в заголовке или cookie.
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Для браузерных запросов без JWT — редирект на /login вместо ответа 401.
            // Для API запросов (Accept: application/json) — стандартный 401.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    String accept = request.getHeader("Accept");
                    if (accept != null && accept.contains("text/html")) {
                        response.sendRedirect("/login");
                    } else {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
                    }
                })
            )

            // Регистрируем JWT-фильтр ПЕРЕД стандартным фильтром Spring Security.
            // Порядок важен: сначала проверяется JWT, потом стандартная аутентификация.
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // BCrypt — алгоритм хэширования паролей, добавляет случайную "соль" к каждому хэшу.
    // Один и тот же пароль даёт разный хэш каждый раз — защита от rainbow table атак.
    // Этот бин используется в AuthService при регистрации и входе.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
