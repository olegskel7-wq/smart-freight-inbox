package com.smartfreight.inbox.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * JPA esybė, atitinkanti lentelę {@code app_user} duomenų bazėje.
 *
 * Naudojama Spring Security autentifikacijai per {@link com.smartfreight.inbox.config.DbUserDetailsService}.
 * Naudotojai kuriami per Flyway migracijas (V2, V3) – ne programiškai.
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    /** Pirminis raktas – automatiškai generuojamas PostgreSQL. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unikalus el. pašto adresas – naudojamas kaip prisijungimo vardas.
     * Spring Security ieško naudotojo pagal šį lauką.
     */
    @Column(name = "email", nullable = false, unique = true)
    private String email;

    /**
     * BCrypt koduotas slaptažodis – niekada nesaugome atviro teksto slaptažodžio.
     * BCrypt automatiškai generuoja druską (salt) kiekvienam slaptažodžiui.
     */
    @Column(name = "password", nullable = false)
    private String password;

    /**
     * Naudotojo vaidmuo: ADMIN, OPERATOR, VIEWER ir pan.
     * Naudojamas autorizacijai – kas ką gali matyti ir redaguoti.
     * Jei null – DbUserDetailsService priskiria numatytąjį vaidmenį OPERATOR.
     */
    @Column(name = "role")
    private String role;
}
