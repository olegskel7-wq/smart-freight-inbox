package com.smartfreight.inbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Конфигурация Spring Security для защиты приложения.
 *
 * Определяет:
 *  1. Какие URL открыты / закрыты (authorizeHttpRequests).
 *  2. Форму входа и logout (formLogin / logout).
 *  3. Алгоритм хеширования паролей (BCryptPasswordEncoder).
 *  4. Бин {@link AuthenticationManager} для программной аутентификации
 *     (используется в {@link com.smartfreight.inbox.service.AuthService} для REST-логина).
 *
 * Источник пользователей — таблица {@code app_user} в БД через
 * {@link DbUserDetailsService}. Spring Security находит его автоматически
 * как единственный бин {@link org.springframework.security.core.userdetails.UserDetailsService}.
 *
 * Изменено относительно прошлой версии:
 *  - удалён {@code InMemoryUserDetailsManager} с хардкоженным demo-юзером:
 *    единый источник учётных записей теперь — БД (см. V2/V3 миграции);
 *  - добавлен бин {@link AuthenticationManager} — нужен сервису
 *    {@link com.smartfreight.inbox.service.AuthService}, чтобы делегировать
 *    проверку пароля Spring Security (а не дублировать логику в коде).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Цепочка фильтров безопасности — основной конфиг авторизации.
     *
     * Правила доступа:
     *  - {@code /actuator/**} — открыто (health-check для LB и мониторинга);
     *  - {@code /admin/**} — открыто (защита проверяется в {@code AdminController} через localhost);
     *  - всё остальное — требуется аутентификация.
     *
     * Form Login (для браузерных клиентов, Thymeleaf UI):
     *  - {@code /login} — страница входа;
     *  - параметр username называется {@code "email"} (а не "username");
     *  - после успешной аутентификации редирект на {@code /tickets};
     *  - ошибка → {@code /login?error=true};
     *  - logout → {@code /login?logout=true}.
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
                // Actuator endpoints открыты для health-checks (Prometheus, LB)
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
                // URL, куда POST'ятся учётные данные (Spring обрабатывает автоматически)
                .loginProcessingUrl("/login")
                // Имя параметра для email (вместо стандартного "username")
                .usernameParameter("email")
                // После успешной аутентификации — редирект сюда
                .defaultSuccessUrl("/tickets", true)
                // После неудачной аутентификации — редирект сюда
                .failureUrl("/login?error=true")
                // Форма входа доступна всем (без проверки auth)
                .permitAll()
            )
            // Logout конфигурация
            .logout(logout -> logout
                // URL для logout (GET или POST)
                .logoutUrl("/logout")
                // После logout — редирект сюда
                .logoutSuccessUrl("/login?logout=true")
                // Logout доступен всем
                .permitAll()
            );

        return http.build();
    }

    /**
     * Кодировщик паролей — BCrypt с автоматической генерацией соли.
     *
     * Свойства BCrypt:
     *  - медленный алгоритм (специально для затруднения brute force);
     *  - автоматическая генерация случайной соли для каждого пароля;
     *  - один и тот же пароль каждый раз даёт разный хэш;
     *  - {@code matches()} сравнивает безопасно (constant time, защита от timing attacks).
     *
     * Используется автоматически Spring Security (через {@code DaoAuthenticationProvider})
     * для проверки введённого пароля против хеша из БД.
     *
     * @return настроенный BCryptPasswordEncoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Бин {@link AuthenticationManager} — точка входа для программной аутентификации.
     *
     * Spring Security сам собирает этот менеджер на основе доступных в контексте
     * провайдеров (в нашем случае — {@code DaoAuthenticationProvider},
     * связанного с {@link DbUserDetailsService} и {@link PasswordEncoder}).
     * Мы лишь публикуем его как @Bean, чтобы внедрить в
     * {@link com.smartfreight.inbox.service.AuthService} для REST-логина.
     *
     * Зачем это нужно:
     *  - устраняет дубликат логики проверки пароля (раньше {@code AuthService}
     *    делал {@code passwordEncoder.matches(...)} вручную);
     *  - единый путь аутентификации и для form login, и для REST API —
     *    проще аудит, проще будущее расширение (lockout, MFA, JWT и т.п.).
     *
     * @param cfg авто-конфигурация Spring Security
     * @return настроенный {@code AuthenticationManager}
     * @throws Exception при ошибке инициализации (Spring требует объявление throws)
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }
}
