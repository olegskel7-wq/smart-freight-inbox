package com.smartfreight.port_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Port Service mikropaslauga – paleidimo taškas.
 *
 * Port Service emuliuoja konteinerių būsenų valdymo sistemą uosto infrastruktūroje.
 * Naudojamas pagrindinio inbox programos konteinerių duomenims gauti
 * (būsena, ETA, vieta).
 *
 * Produkcijoje ši sistema būtų integruota su realia TOS/CMS
 * (Terminal Operating System), tačiau demonstracijos tikslais
 * naudojama paprasta emuliacija su automatiniu būsenų atnaujinimu.
 *
 * @see com.smartfreight.port_service.scheduler.StatusUpdateScheduler – periodinis būsenų atnaujinimas
 * @see com.smartfreight.port_service.controller.ContainerController – REST API konteinerių užklausoms
 */
@SpringBootApplication
@EnableScheduling
public class PortServiceApplication {

    /** Programos paleidimo metodas – paleidžia Spring kontekstą. */
    public static void main(String[] args) {
        SpringApplication.run(PortServiceApplication.class, args);
    }
}
