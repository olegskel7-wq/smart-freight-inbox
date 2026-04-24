package com.smartfreight.inbox;

import org.springframework.boot.SpringApplication;

/**
 * Entry point для запуска тестов через IDE или CLI с Testcontainers.
 * 
 * Автоматически обнаруживает и использует Docker для поднятия PostgreSQL контейнера
 * при запуске интеграционных тестов.
 * 
 * Используется через: java TestInboxApplication
 * или через IDE "Run as application" с target = @TestcontainersConfiguration.class
 */
public class TestInboxApplication {

	public static void main(String[] args) {
		// from() — альтернативный способ создания приложения (новый API Spring Boot 3.0+)
		// with() — добавляет конфигурацию (TestcontainersConfiguration)
		SpringApplication.from(InboxApplication::main)
				.with(TestcontainersConfiguration.class)
				.run(args);
	}

}
