package com.smartfreight.port_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke test for the port-service microservice.
 *
 * Verifies that the application starts without errors:
 * 1. Spring context is initialised successfully.
 * 2. All components are created and wired.
 * 3. DB migrations run (Flyway).
 * 4. Configuration is valid.
 *
 * {@code @SpringBootTest} raises the full context (same as a real startup).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "port.api.key=test-api-key-smoke")
class PortServiceApplicationTests {

    @Test
    void contextLoads() {
        // Empty by design: context loading itself is the assertion.
    }
}
