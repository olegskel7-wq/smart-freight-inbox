package com.smartfreight.inbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Pagrindinis programos dūmų (smoke) testas.
 *
 * Tikrina:
 * 1. Spring kontekstas paleidžiamas be klaidų
 * 2. Visi bean'ai sukonfigūruoti teisingai
 * 3. Priklausomybių įterpimas (dependency injection) veikia
 * 4. Flyway migracijos sėkmingai įvykdytos
 *
 * @SpringBootTest – paleidžia pilną kontekstą (kaip realiai paleidus programą)
 * @Import(TestcontainersConfiguration.class) – naudoja Testcontainers DB:
 *   testas paleidžia savo PostgreSQL Docker konteinerį automatiškai.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InboxApplicationTests {

	/**
	 * Dūmų testas: tiesiog tikriname, kad programa sėkmingai paleidžiama.
	 *
	 * Jei šis testas praeina – reiškia:
	 * – Spring konfigūracija tinkama
	 * – Visi bean'ai sukurti
	 * – Priklausomybės susietos teisingai
	 * – DB migruota ir paruošta
	 */
	@Test
	void contextLoads() {
	}

}
