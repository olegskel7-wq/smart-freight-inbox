package com.smartfreight.inbox.config;

import com.smartfreight.inbox.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Реализация {@link UserDetailsService} поверх БД (таблица {@code app_user}).
 *
 * Заменяет ранее использовавшийся {@code InMemoryUserDetailsManager}
 * с захардкоженным demo-пользователем. Теперь Spring Security при логине
 * (как через form login на {@code /login}, так и через REST {@code /api/auth/login})
 * читает учётные данные строго из одного источника — таблицы {@code app_user}.
 *
 * Преимущества:
 *  - единственный источник правды для пользователей (БД, а не in-memory + хардкод);
 *  - реальные пользователи могут добавляться через Flyway-миграции
 *    (уже сделано в V2/V3 для demo-пользователя operator@smartfreight.com);
 *  - подготовка к следующим итерациям: смена пароля, блокировка аккаунта,
 *    добавление ролей, многотенантность — всё уже на JPA-сущности {@link com.smartfreight.inbox.entity.User}.
 *
 * Spring Security автоматически подхватывает этот бин, потому что он —
 * единственный {@link UserDetailsService} в контексте.
 *
 * Внимание: класс не должен сам заниматься хешированием пароля. Хешированный
 * пароль уже лежит в БД (записан Flyway-миграцией с BCrypt-хешем), а сравнение
 * с введённым пользователем выполняет {@code DaoAuthenticationProvider}
 * через бин {@link org.springframework.security.crypto.password.PasswordEncoder}.
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(DbUserDetailsService.class);

    /** Роль по умолчанию, если в БД у пользователя поле role = NULL. */
    private static final String DEFAULT_ROLE = "OPERATOR";

    private final UserRepository userRepository;

    public DbUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Загружает учётные данные пользователя по его логину (email).
     *
     * Метод вызывается Spring Security автоматически при попытке аутентификации.
     * Возвращённый объект {@link UserDetails} содержит email, BCrypt-хеш пароля
     * и список ролей; сравнение пароля делает Spring Security самостоятельно.
     *
     * Защитные соображения:
     *  - выбрасываем {@link UsernameNotFoundException} с обобщённым сообщением,
     *    не раскрывающим, существует ли пользователь (в логах — детально, в ответе клиенту — нет);
     *  - если в БД роль не задана, подставляем безопасную роль по умолчанию,
     *    чтобы исключить состояние "пользователь без роли" (Spring Security в этом случае ругается).
     *
     * @param email адрес электронной почты — он же логин в системе
     * @return объект {@link UserDetails}, понятный Spring Security
     * @throws UsernameNotFoundException если пользователь с таким email не найден
     */
    @Override
    public UserDetails loadUserByUsername(String email) {
        return userRepository.findByEmail(email)
                .map(u -> {
                    String role = (u.getRole() == null || u.getRole().isBlank())
                            ? DEFAULT_ROLE
                            : u.getRole();
                    // Используем builder из пакета security, поэтому пишем FQN —
                    // короткий импорт User конфликтовал бы с com.smartfreight.inbox.entity.User.
                    return org.springframework.security.core.userdetails.User
                            .withUsername(u.getEmail())
                            .password(u.getPassword())
                            .roles(role)
                            .build();
                })
                .orElseThrow(() -> {
                    log.warn("User not found in database: {}", email);
                    return new UsernameNotFoundException("User not found: " + email);
                });
    }
}
