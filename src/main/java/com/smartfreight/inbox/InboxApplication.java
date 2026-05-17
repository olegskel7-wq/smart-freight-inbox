package com.smartfreight.inbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

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

    /** Application startup method. */
    public static void main(String[] args) {
        SpringApplication.run(InboxApplication.class, args);
    }
}
