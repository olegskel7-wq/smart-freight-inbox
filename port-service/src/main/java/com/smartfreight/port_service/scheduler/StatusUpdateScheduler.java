package com.smartfreight.port_service.scheduler;

import com.smartfreight.port_service.entity.Container;
import com.smartfreight.port_service.repository.ContainerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Suplanuota užduotis, emuliuojanti konteinerių būsenų atnaujinimą uoste.
 *
 * Produkcijoje šie atnaujinimai ateitų iš realios uosto valdymo sistemos (TOS/CMS).
 * Čia naudojama paprasta emuliacija: kas N minučių atsitiktinis konteineris
 * perkeliamas į kitą būseną.
 *
 * Konteinerio gyvavimo ciklas:
 * IN_TRANSIT → AT_PORT → CUSTOMS → RELEASED → DELIVERED
 *
 * Po DELIVERED būsena cikliškai grįžta į IN_TRANSIT (demonstracijos tikslais).
 *
 * Paskirtis:
 * – Parodyti duomenų atnaujinimą realiuoju laiku
 * – Testuoti inbox programos elgseną keičiantis būsenoms
 * – Demonstracijai: „žiūrėkite, būsenos iš tikrųjų atnaujinamos kas 5 minutes"
 */
@Component
public class StatusUpdateScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatusUpdateScheduler.class);

    /**
     * Konteinerio gyvavimo ciklas – būsenų seka krovinių judėjimo metu.
     *
     * Kiekvienas konteineris pereina šiuos etapus nurodyta tvarka:
     * 1. IN_TRANSIT – konteineris keliauja laive arba sunkvežimyje
     * 2. AT_PORT – atvyko į uosto terminalą ir iškrautas
     * 3. CUSTOMS – tikrinamas muitinėje (laikomas muitinės sandėlyje)
     * 4. RELEASED – muitinė patvirtino išleidimą (konteineris pasiruošęs paėmimui)
     * 5. DELIVERED – pristatytas galutiniam gavėjui / klientui
     */
    private static final List<String> STATUS_CYCLE = List.of(
            "IN_TRANSIT",
            "AT_PORT",
            "CUSTOMS",
            "RELEASED",
            "DELIVERED"
    );

    private final ContainerRepository containerRepository;
    private final Random random = new Random();

    public StatusUpdateScheduler(ContainerRepository containerRepository) {
        this.containerRepository = containerRepository;
    }

    /**
     * Periodinė konteinerių būsenų atnaujinimo užduotis.
     *
     * Grafikas:
     * – fixedDelayString nuskaito intervalą iš application.yaml (raktas: port.scheduler.interval-ms)
     * – Numatytoji reikšmė: 300 000 ms = 5 minutės
     * – fixedDelay garantuoja, kad kitas paleidimas pradės ne anksčiau nei baigs dabartinis
     *   (skirtingai nei fixedRate, kuris paleidžia kas N ms nepriklausomai nuo vykdymo trukmės)
     *
     * Procesas:
     * 1. Gauname visus konteinerius iš DB
     * 2. Maišome juos (Collections.shuffle) atsitiktiniam pasirinkimui
     * 3. Pasirenkame 1–2 atsitiktinius konteinerius
     * 4. Kiekvienam: perkeliame į kitą būseną, išsaugome DB
     * 5. Žurnale fiksuojame pakeitimą
     */
    @Scheduled(fixedDelayString = "${port.scheduler.interval-ms:300000}")
    public void updateRandomContainerStatuses() {
        // Kopijuojame sąrašą, nes Collections.shuffle reikalauja keičiamo sąrašo
        List<Container> all = new java.util.ArrayList<>(containerRepository.findAll());
        if (all.isEmpty()) {
            return;
        }

        Collections.shuffle(all, random);

        // random.nextInt(2) grąžina 0 arba 1; +1 → rezultatas 1 arba 2
        int count = random.nextInt(2) + 1;

        all.stream()
                .limit(count)
                .forEach(container -> {
                    String oldStatus = container.getStatus();
                    String newStatus = nextStatus(oldStatus);
                    container.setStatus(newStatus);
                    container.setUpdatedAt(LocalDateTime.now());
                    containerRepository.save(container);
                    log.info("Container {} status: {} → {}",
                            container.getContainerNumber(), oldStatus, newStatus);
                });
    }

    /**
     * Apskaičiuoja kitą būseną konteinerio gyvavimo cikle.
     *
     * Logika:
     * – Randame dabartinės būsenos indeksą STATUS_CYCLE sąraše
     * – Pereiname į kitą: (index + 1) % SIZE
     * – Modulis (%) užtikrina ciklinį perėjimą: paskutinė būsena → pirmoji
     *
     * Pavyzdžiai:
     * – nextStatus("IN_TRANSIT") → "AT_PORT" (indeksas 0 → 1)
     * – nextStatus("DELIVERED") → "IN_TRANSIT" (indeksas 4 → 0 per modulį)
     * – nextStatus("UNKNOWN") → "IN_TRANSIT" (nežinoma būsena → ciklo pradžia)
     *
     * @param current dabartinė konteinerio būsena
     * @return kita būsena cikle
     */
    String nextStatus(String current) {
        // Jei būsena null arba nežinoma – pradedame ciklą iš pradžių.
        // List.of() neleidžia iškviesti indexOf(null), todėl tikriname iš anksto.
        if (current == null) {
            return STATUS_CYCLE.get(0);
        }

        int index = STATUS_CYCLE.indexOf(current);
        if (index == -1) {
            return STATUS_CYCLE.get(0);
        }

        return STATUS_CYCLE.get((index + 1) % STATUS_CYCLE.size());
    }
}
