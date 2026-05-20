package com.smartfreight.port_service;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Port Service microservice – application entry point.
 *
 * Port Service simulates a container status management system within port infrastructure.
 * It is used by the main inbox application to retrieve container data
 * (status, ETA, location).
 *
 * In production this system would be integrated with a real TOS/CMS
 * (Terminal Operating System), but for demonstration purposes
 * a simple simulation with automatic status updates is used.
 *
 * @see com.smartfreight.port_service.scheduler.StatusUpdateScheduler – periodic status updates
 * @see com.smartfreight.port_service.controller.ContainerController – REST API for container queries
 */
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT6M")
public class PortServiceApplication {

    /** Application entry method – starts the Spring context. */
    public static void main(String[] args) {
        SpringApplication.run(PortServiceApplication.class, args);
    }
}
