package com.smartfreight.inbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * Smart Freight Inbox — application entry point.
 *
 * @SpringBootApplication enables:
 * — auto-configuration
 * — component scan
 * — Spring context configuration
 *
 * @EnableScheduling enables periodic task execution via @Scheduled
 * (required for {@link com.smartfreight.inbox.scheduler.RetryScheduler},
 * which periodically triggers reprocessing of RETRY_LATER tickets).
 */
@SpringBootApplication
@EnableScheduling
public class InboxApplication {

    /**
     * Application startup method. The JVM zone is pinned to UTC: the API emits zone-less
     * {@code LocalDateTime}s (from {@code LocalDateTime.now()}) and the SPA reads them as UTC, so a run
     * from a BST laptop must not stamp local wall-clock time. The backend container is UTC anyway.
     */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(InboxApplication.class, args);
    }
}
