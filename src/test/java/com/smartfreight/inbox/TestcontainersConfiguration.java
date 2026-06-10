package com.smartfreight.inbox;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Конфигурация Testcontainers для интеграционных тестов.
 * 
 * Автоматически запускает Docker контейнер с PostgreSQL перед тестами
 * и связывает его с приложением (не трогает вашу рабочую БД на порту 5433).
 * 
 * @TestConfiguration — конфигурация только для тестов (не используется в production)
 * @ServiceConnection — Spring Boot автоматически создаёт JDBC datasource с параметрами контейнера
 * 
 * Требования:
 * - Docker установлен и запущен
 * - Testcontainers на classpath (добавлена в pom.xml <scope>test</scope>)
 * - PostgreSQL контейнер доступен в Docker Registry (docker.io)
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/**
	 * Bean для управления PostgreSQL Docker контейнером.
	 * 
	 * Жизненный цикл:
	 * 1. @BeforeAll (один раз перед всеми тестами): контейнер запускается, БД инициализируется
	 * 2. Flyway миграции выполняются (создаём таблицы)
	 * 3. Тесты выполняются (используют эту БД через JDBC datasource)
	 * 4. @AfterAll (один раз после всех тестов): контейнер останавливается и удаляется
	 * 
	 * @ServiceConnection — Spring Boot читает параметры контейнера
	 *                     (хост, порт, username, password) и автоматически создаёт
	 *                     JDBC datasource (не нужно вручную писать spring.datasource.url)
	 * 
	 * @return контейнер PostgreSQL (его управляет Testcontainers)
	 */
	@Bean
	@ServiceConnection
	public PostgreSQLContainer<?> postgresContainer() {
		return new PostgreSQLContainer<>("postgres:16-alpine");
	}

	/**
	 * RabbitMQ контейнер для асинхронного AI-пайплайна (Phase 1.3).
	 *
	 * Smoke-тест с реальным брокером проверяет всю messaging-инфраструктуру:
	 * декларацию топологии (exchange, очереди, DLQ-биндинги) и старт
	 * listener-контейнера TicketEventConsumer.
	 *
	 * @ServiceConnection — Spring Boot подставляет host/port/credentials контейнера
	 *                      в spring.rabbitmq.* автоматически.
	 */
	@Bean
	@ServiceConnection
	public RabbitMQContainer rabbitContainer() {
		return new RabbitMQContainer("rabbitmq:3.13-alpine");
	}

}
