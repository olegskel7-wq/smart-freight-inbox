package com.smartfreight.inbox;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Конфигурация Testcontainers для интеграционных тестов.
 *
 * Автоматически запускает Docker контейнеры (PostgreSQL, RabbitMQ) перед тестами
 * и связывает их с приложением (не трогает вашу рабочую БД на порту 5433).
 *
 * Требования:
 * - Docker установлен и запущен
 * - Testcontainers на classpath (добавлена в pom.xml <scope>test</scope>)
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/**
	 * PostgreSQL контейнер.
	 *
	 * Phase 2.2: БЕЗ @ServiceConnection — креды раздаются вручную ниже, потому что
	 * приложение и Flyway должны подключаться под РАЗНЫМИ ролями, как в проде:
	 * Flyway — под админом контейнера (DDL, CREATE ROLE, политики RLS),
	 * приложение — под smartfreight_app (NOSUPERUSER, NOBYPASSRLS — роль создаёт V19).
	 * Юзер контейнера — суперюзер, RLS его игнорирует: тесты изоляции под ним
	 * молча проверяли бы пустоту.
	 *
	 * Phase 3.1: образ pgvector/pgvector:pg16 (вместо postgres:16-alpine) — V28 делает
	 * CREATE EXTENSION vector. asCompatibleSubstituteFor("postgres") убеждает Testcontainers,
	 * что это PostgreSQL-совместимый образ (иначе он не распознаёт нестандартное имя).
	 */
	@Bean
	public PostgreSQLContainer<?> postgresContainer() {
		return new PostgreSQLContainer<>(
				DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
	}

	/**
	 * Разводка кредов по ролям (см. postgresContainer). Регистратор зависит от бина
	 * контейнера, поэтому Boot стартует контейнер до резолва свойств; пароль роли
	 * smartfreight_app берётся из дефолта плейсхолдера app_db_password в application.yaml.
	 */
	@Bean
	public DynamicPropertyRegistrar postgresProperties(PostgreSQLContainer<?> postgres) {
		return registry -> {
			registry.add("spring.datasource.url", postgres::getJdbcUrl);
			registry.add("spring.datasource.username", () -> "smartfreight_app");
			registry.add("spring.datasource.password", () -> "smartfreight_app");
			registry.add("spring.flyway.url", postgres::getJdbcUrl);
			registry.add("spring.flyway.user", postgres::getUsername);
			registry.add("spring.flyway.password", postgres::getPassword);
		};
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

	/**
	 * Redis контейнер (Phase 2.6) — session store, кэш rate sheets, login rate-limit счётчики.
	 * Без него полный контекст не стартует: автоконфиг Spring Session / cache.type=redis ждёт
	 * соединение. @ServiceConnection(name="redis") подставляет host/port в spring.data.redis.*.
	 */
	@Bean
	@ServiceConnection(name = "redis")
	public GenericContainer<?> redisContainer() {
		return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	}

}
