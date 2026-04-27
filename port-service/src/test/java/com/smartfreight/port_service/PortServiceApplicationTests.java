package com.smartfreight.port_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Dūmų testas (smoke test) mikropaslaugai port-service.
 *
 * Patikrina, kad programa paleidžiama be klaidų:
 * 1. Spring kontekstas inicializuojamas sėkmingai
 * 2. Visi komponentai sukuriami ir sujungiami
 * 3. DB migruojama (Flyway)
 * 4. Konfigūracija galiojanti
 *
 * {@code @SpringBootTest} iškelia pilną kontekstą (kaip realiame paleidime).
 */
@SpringBootTest
class PortServiceApplicationTests {

    /**
     * Jei testas praėjo – konfigūracija teisinga.
     * Jei išmesta išimtis – yra konfigūracijos klaida.
     */
    @Test
    void contextLoads() {
        // Tuščias tyčia: pats konteksto įkėlimas yra pakankamas patikrinimas
    }
}
