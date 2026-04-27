package com.smartfreight.port_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * JPA esybė, atitinkanti konteinerio įrašą duomenų bazėje.
 *
 * Vaizduoja krovinio konteinerį su dabartinės būsenos, vietos ir ETA informacija.
 * Šie duomenys naudojami pagrindiniame inbox programoje atsakymų generavimui
 * į klientų el. pašto užklausas apie krovinių būseną.
 *
 * Duomenų pavyzdžiai:
 * – containerNumber = "MSCU1234567" (ISO 6346 formatas: 4 raidės + 7 skaitmenys)
 * – status = "AT_PORT" (galimos reikšmės: IN_TRANSIT, AT_PORT, CUSTOMS, RELEASED, DELIVERED)
 * – location = "Felixstowe Terminal 2" (uosto terminalo vieta)
 * – eta = 2026-04-25T14:30:00 (numatomas atvykimo / išleidimo laikas)
 */
@Entity
@Table(name = "container")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Container {

    /** Pirminis raktas – unikalus ID duomenų bazėje. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Konteinerio numeris pagal ISO 6346 standartą.
     *
     * Formatas: XXXX1234567 (4 raidės + 7 skaitmenys).
     * Pavyzdžiai: MSCU1234567, OOLU9876543, HLXU5555555.
     *
     * unique = true – DB neleidžia dviejų konteinerių su tuo pačiu numeriu.
     */
    @Column(name = "container_number", nullable = false, unique = true)
    private String containerNumber;

    /**
     * Dabartinė konteinerio būsena uosto sistemoje.
     *
     * Gyvavimo ciklas (eilės tvarka):
     * 1. IN_TRANSIT – konteineris keliauja (laive / sunkvežimyje)
     * 2. AT_PORT – konteineris atvyko į uosto terminalą
     * 3. CUSTOMS – konteineris tikrinamas muitinėje
     * 4. RELEASED – muitinė patvirtino krovinio išleidimą
     * 5. DELIVERED – konteineris pristatytas klientui
     *
     * {@link com.smartfreight.port_service.scheduler.StatusUpdateScheduler}
     * periodiškai keičia būsenas (emuliacija).
     */
    @Column(name = "status")
    private String status;

    /**
     * Numatomas atvykimo į uostą arba išleidimo klientui laikas (ETA).
     * Naudojamas atsakyme: "Jūsų konteineris turėtų atvykti balandžio 25 d. 14:30".
     */
    @Column(name = "eta")
    private LocalDateTime eta;

    /**
     * Dabartinė geografinė konteinerio vieta.
     *
     * Pavyzdžiai:
     * – "Felixstowe Terminal 2" (uosto terminalas)
     * – "Customs Warehouse A" (muitinės sandėlis)
     * – "Transit to client" (keliauja pas klientą)
     */
    @Column(name = "location")
    private String location;

    /**
     * Paskutinio konteinerio informacijos atnaujinimo laikas.
     * Naudojamas derinimui ir auditui.
     * {@link com.smartfreight.port_service.scheduler.StatusUpdateScheduler}
     * automatiškai atnaujina šį lauką keičiant būseną.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
