package com.smartfreight.port_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Dūmų testas (smoke test) mikropaslaugai port-service.
 *
 * Patikrina, kad programa paleidžiama be klaidų:
 * 1. Spring kontekstas inicializuojamas sėkmingai
 * 2. Visi komponentai sukuriami ir sujungiami
 * 3. DB migruojama per Flyway (realus PostgreSQL per Testcontainers)
 * 4. Konfigūracija galiojanti
 *
 * {@link TestcontainersConfiguration} automatiškai paleidžia PostgreSQL konteinerį
 * ir konfigūruoja duomenų šaltinį – testas nepriklausomas nuo lokalios DB.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "port.api.key=test-api-key-smoke")
class PortServiceApplicationTests {

    /**
     * Jei testas praėjo – konfigūracija teisinga ir Flyway migracija sėkminga.
     * Jei išmesta išimtis – yra konfigūracijos arba migracijos klaida.
     */
    @Test
    void contextLoads() {
        // Tuščias tyčia: pats konteksto įkėlimas yra pakankamas patikrinimas
    }
}
