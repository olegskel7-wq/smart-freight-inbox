package com.smartfreight.inbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Smart Freight Inbox – programos paleidimo taškas.
 *
 * @SpringBootApplication įjungia:
 * – automatinę konfigūraciją (auto-configuration)
 * – komponentų nuskaitymą (component scan)
 * – Spring konteksto konfigūraciją
 *
 * @EnableScheduling įjungia periodinių užduočių vykdymą per @Scheduled
 * (reikalinga {@link com.smartfreight.inbox.scheduler.RetryScheduler},
 * kuris periodiškai paleidžia RETRY_LATER biletų persvarstymas).
 */
@SpringBootApplication
@EnableScheduling
public class InboxApplication {

    /** Programos paleidimo metodas. */
    public static void main(String[] args) {
        SpringApplication.run(InboxApplication.class, args);
    }
}
