package com.smartfreight.port_service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Testcontainers konfigūracija integraciniams testams.
 *
 * Automatiškai paleidžia PostgreSQL konteinerį ir konfigūruoja
 * Spring Boot duomenų šaltinį per @ServiceConnection anotaciją
 * (nereikia rankiniu būdu registruoti DynamicPropertySource).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
