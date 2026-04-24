package com.smartfreight.inbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Базовый smoke test приложения.
 * 
 * Проверяет что:
 * 1. Spring контекст поднимается без ошибок
 * 2. Все бины конфигурируются корректно
 * 3. Нет проблем с зависимостями (dependency injection)
 * 
 * @SpringBootTest — поднимает полный контекст (как при запуске приложения)
 * @Import(TestcontainersConfiguration.class) — использует Testcontainers для БД
 *                                               (тест стартует собственный PostgreSQL в Docker)
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InboxApplicationTests {

	/**
	 * Smoke test: просто проверяем что приложение стартует.
	 * 
	 * Если этот тест проходит — значит:
	 * - Spring конфигурация валидна
	 * - Все бины созданы
	 * - Зависимости связаны корректно
	 * - БД мигрирована и готова
	 */
	@Test
	void contextLoads() {
	}

}
