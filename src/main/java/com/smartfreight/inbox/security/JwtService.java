package com.smartfreight.inbox.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

// @Service — Spring сервис для работы с JWT (Java Web Tokens)
@Service
public class JwtService {

    // Секретный ключ для подписания и проверки JWT
    // Читается из переменной окружения JWT_SECRET или application.yaml
    // Должен быть достаточно длинным (минимум 32 символа для HMAC-SHA256)
    @Value("${jwt.secret}")
    private String jwtSecret;

    // Время жизни JWT токена: 24 часа в миллисекундах
    private static final long JWT_EXPIRATION = 86400000;

    // Извлечь email пользователя из JWT токена
    // Это делается путём парсинга payload'а (тела) токена
    public String extractUsername(String token) {
        return getClaimsFromToken(token).getSubject();
    }

    // Проверить: действителен ли JWT токен?
    // Токен валиден если email совпадает и срок не истёк
    public boolean isTokenValid(String token, String username) {
        try {
            final String tokenUsername = extractUsername(token);
            return tokenUsername.equals(username) && !isTokenExpired(token);
        } catch (Exception e) {
            // Любая ошибка при парсинге (неправильная подпись, неверный формат) = невалидный токен
            return false;
        }
    }

    // Проверить: истёк ли срок действия токена?
    public boolean isTokenExpired(String token) {
        return getClaimsFromToken(token).getExpiration().before(new Date());
    }

    // Парсить JWT и получить содержимое (Claims = payload токена)
    // В jjwt 0.12.x используется Jwts.parser() вместо устаревшего Jwts.parserBuilder()
    private Claims getClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())   // указываем ключ для проверки подписи
                .build()
                .parseSignedClaims(token)      // парсим и проверяем подпись
                .getPayload();                 // получаем payload (содержимое)
    }

    // Получить секретный ключ для подписания/проверки токена
    // Ключ кодируется из строки jwtSecret в формат для HMAC-SHA256
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }
}
