package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// @Repository — Spring DAO для работы с пользователями
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // Найти пользователя по email-адресу
    // Используется при аутентификации (логин) и проверке наличия пользователя в JwtService
    Optional<User> findByEmail(String email);
}
