package com.smartfreight.port_service;

import com.smartfreight.port_service.entity.ContainerStatus;
import com.smartfreight.port_service.repository.ContainerRepository;
import com.smartfreight.port_service.scheduler.StatusUpdateScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

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
    @Autowired
    private ApplicationContext context;

    @Autowired
    private ContainerRepository containerRepository;

    @Test
    void contextLoads() {
        // Tuščias tyčia: pats konteksto įkėlimas yra pakankamas patikrinimas
    }

    /** The diploma-demo status simulator is off unless port.scheduler.enabled=true. */
    @Test
    void statusSimulatorIsOffByDefault() {
        assertThat(context.getBeansOfType(StatusUpdateScheduler.class)).isEmpty();
    }

    /** V8 leaves every container at its seed status (CMAU5551234 is the ticket-118 case). */
    @Test
    void seedStatusesAreIntact() {
        assertThat(containerRepository.findByContainerNumber("CMAU5551234"))
                .hasValueSatisfying(c -> assertThat(c.getStatus()).isEqualTo(ContainerStatus.CUSTOMS));
    }
}
