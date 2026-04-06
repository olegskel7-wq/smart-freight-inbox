package com.smartfreight.inbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// @SpringBootApplication — главная аннотация Spring Boot:
// включает автоконфигурацию, сканирование компонентов и настройку контекста
@SpringBootApplication
// @EnableAsync — разрешает асинхронное выполнение методов, помеченных @Async
// (нужно для TicketProcessingService, который запускает AI в отдельных потоках)
@EnableAsync
// @EnableScheduling — разрешает планирование задач по расписанию через @Scheduled
// (нужно для RetryScheduler, который периодически перезапускает тикеты RETRY_LATER)
@EnableScheduling
public class InboxApplication {

	// Точка входа в приложение: запускает Spring контекст и все компоненты
	public static void main(String[] args) {
		SpringApplication.run(InboxApplication.class, args);
	}

}
