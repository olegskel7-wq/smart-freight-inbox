package com.smartfreight.inbox.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

// @Entity — JPA сущность для таблицы пользователей
@Entity
// @Table — имя таблицы в БД
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    // Первичный ключ
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Уникальный email пользователя — используется как логин при аутентификации
    @Column(name = "email", nullable = false, unique = true)
    private String email;

    // Хэшированный пароль (никогда не храним пароль в открытом виде!)
    // Используется BCrypt — алгоритм с "солью" (random bytes в хэше)
    // Один и тот же пароль даёт разный хэш каждый раз
    @Column(name = "password", nullable = false)
    private String password;

    // Роль пользователя: ADMIN, OPERATOR, VIEWER и т.д.
    // Используется для авторизации (кто может видеть/редактировать данные)
    @Column(name = "role")
    private String role;
}
