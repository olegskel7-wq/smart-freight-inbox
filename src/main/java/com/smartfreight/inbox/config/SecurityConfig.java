package com.smartfreight.inbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security konfigūracija programos apsaugai.
 *
 * Naudoja formų prisijungimą (form login) – tinkama naršyklės klientams
 * su Thymeleaf UI. Sesija saugoma JSESSIONID cookie.
 *
 * Prieigos taisyklės:
 * – {@code /actuator/**} – atvirai (health-check apkrovos balansuotojui ir Prometheus)
 * – {@code /admin/**} – atvirai (apsauga per localhost tikrinimą AdminController viduje)
 * – viskas kita – reikalinga autentifikacija
 *
 * Naudotojų šaltinis – {@code app_user} lentelė DB per {@link DbUserDetailsService}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Saugumo filtrų grandinė – pagrindinė autorizacijos konfigūracija.
     *
     * Formų prisijungimas (naršyklės klientams, Thymeleaf UI):
     * – {@code /login} – prisijungimo formos puslapis
     * – naudotojo vardo parametras: {@code "email"} (ne standartinis "username")
     * – sėkmės atveju nukreipiama į {@code /tickets}
     * – klaidos atveju → {@code /login?error=true}
     * – atsijungus → {@code /login?logout=true}
     *
     * @param http HttpSecurity konfigūratorius
     * @return sukonfigūruota filtrų grandinė
     * @throws Exception konfigūracijos klaidos atveju
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/admin/**").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("email")
                .defaultSuccessUrl("/tickets", true)
                .failureUrl("/login?error=true")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout=true")
                .permitAll()
            );

        return http.build();
    }

    /**
     * BCrypt slaptažodžių koduoklis su automatiškai generuojama druska.
     *
     * Naudojamas {@code DaoAuthenticationProvider} automatiškai
     * per {@link DbUserDetailsService} prisijungimo tikrinimui.
     *
     * @return sukonfigūruotas BCryptPasswordEncoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
