package com.smartfreight.inbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Конфигурация Spring Security для защиты приложения.
 * 
 * Определяет:
 * 1. Какие URL открыты / закрыты (authorizeHttpRequests)
 * 2. Форму входа и logout (formLogin)
 * 3. Хранилище пользователей (UserDetailsService)
 * 4. Алгоритм хеширования паролей (BCryptPasswordEncoder)
 * 
 * На v1 используется встроенное хранилище в памяти (InMemoryUserDetailsManager).
 * На v2 переключиться на UserRepository + JdbcUserDetailsManager.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Цепочка фильтров безопасности — основной конфиг авторизации.
     * 
     * Правила доступа:
     * - /actuator/** — открыто (health check для LB и мониторинга)
     * - /admin/** — открыто (защита проверяется в AdminController через localhost)
     * - Всё остальное — требуется аутентификация
     * 
     * Form Login:
     * - /login — страница входа (может быть GET или POST по настройкам)
     * - Параметр username называется "email" (не "username")
     * - После успешной аутентификации редирект на /tickets
     * - Ошибка → /login?error=true
     * - Logout → /login?logout=true
     * 
     * @param http конфигуратор HttpSecurity
     * @return цепочка фильтров
     * @throws Exception при ошибке конфигурации
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Авторизация: какие URL видны без логина
            .authorizeHttpRequests(auth -> auth
                // Actuator endpoints открыты для health checks (Prometheus, LB)
                .requestMatchers("/actuator/**").permitAll()
                // Admin endpoints открыты (но проверка localhost внутри контроллера)
                .requestMatchers("/admin/**").permitAll()
                // Всё остальное требует аутентификации
                .anyRequest().authenticated()
            )
            // Form Login конфигурация (используется браузерными клиентами)
            .formLogin(form -> form
                // URL страницы входа
                .loginPage("/login")
                // URL, куда POST'ить учётные данные (Spring автоматически обрабатывает)
                .loginProcessingUrl("/login")
                // Имя параметра для email (вместо стандартного "username")
                .usernameParameter("email")
                // После успешной аутентификации редирект сюда
                .defaultSuccessUrl("/tickets", true)
                // После неудачной аутентификации редирект сюда
                .failureUrl("/login?error=true")
                // Форма входа видна всем (без проверки auth)
                .permitAll()
            )
            // Logout конфигурация
            .logout(logout -> logout
                // URL для logout (GET или POST)
                .logoutUrl("/logout")
                // После logout редирект сюда
                .logoutSuccessUrl("/login?logout=true")
                // Logout доступен всем
                .permitAll()
            );

        return http.build();
    }

    /**
     * Хранилище пользователей для аутентификации.
     * 
     * На v1: встроенное хранилище в памяти (InMemoryUserDetailsManager) с demo пользователем.
     * На v2: переключиться на UserRepository + JdbcUserDetailsManager для scalability.
     * 
     * Demo пользователь:
     * - Email: operator@smartfreight.com
     * - Пароль: demo123 (хеширован BCrypt)
     * - Роль: OPERATOR
     * 
     * @param passwordEncoder бин BCryptPasswordEncoder для хеширования
     * @return UserDetailsService для Spring Security
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        // Создаём demo пользователя с BCrypt хешированным паролем
        var demoUser = User.builder()
                .username("operator@smartfreight.com")
                // Хешируем пароль перед сохранением
                .password(passwordEncoder.encode("demo123"))
                // Роль для авторизации (@Secured, @RolesAllowed и т.п.)
                .roles("OPERATOR")
                .build();
        // В памяти хранится только этот один пользователь
        return new InMemoryUserDetailsManager(demoUser);
    }

    /**
     * Кодировщик паролей — BCrypt с автоматической генерацией соли.
     * 
     * Свойства BCrypt:
     * - Медленный алгоритм (специально для затруднения brute force)
     * - Автоматическая генерация случайной соли для каждого пароля
     * - Один и тот же пароль в открытом виде каждый раз даёт разный хэш
     * - matches() сравнивает безопасно (constant time, защита от timing attacks)
     * 
     * @return настроенный BCryptPasswordEncoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
