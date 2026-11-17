package com.smartfreight.port_service.scheduler;

import com.smartfreight.port_service.entity.Container;
import com.smartfreight.port_service.entity.ContainerStatus;
import com.smartfreight.port_service.repository.ContainerRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scheduled task that simulates container status updates in the port.
 *
 * In production these updates would come from a real Terminal/Cargo Management System (TOS/CMS).
 * Here a simple simulation is used: every N minutes a random container
 * is advanced to the next status.
 *
 * Container lifecycle:
 * IN_TRANSIT → AT_PORT → CUSTOMS → RELEASED → DELIVERED
 *
 * After DELIVERED the status cycles back to IN_TRANSIT (for demonstration purposes).
 *
 * Purpose:
 * – Demonstrate real-time data updates
 * – Test the inbox application's behaviour as statuses change
 * – Demo talking point: "statuses are actually updated every 5 minutes"
 *
 * OFF by default ({@code port.scheduler.enabled=false}): it was built for the diploma defence demo.
 * It advances only the status, never the location or ETA, so the data it leaves behind contradicts
 * itself ("IN_TRANSIT" at "HMRC Examination Bay 3") and the inbox drafts repeat the contradiction.
 * Set {@code SCHEDULER_ENABLED=true} to bring it back for a live-update demo.
 */
@Component
@ConditionalOnProperty(name = "port.scheduler.enabled", havingValue = "true")
public class StatusUpdateScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatusUpdateScheduler.class);

    /**
     * Container lifecycle – the sequence of statuses during cargo movement.
     *
     * Each container passes through the following stages in order:
     * 1. IN_TRANSIT – container is travelling by ship or truck
     * 2. AT_PORT – arrived at the port terminal and unloaded
     * 3. CUSTOMS – undergoing customs inspection (held in customs warehouse)
     * 4. RELEASED – customs has approved release (container ready for collection)
     * 5. DELIVERED – delivered to the final recipient / customer
     */
    private static final List<ContainerStatus> STATUS_CYCLE = List.of(
            ContainerStatus.IN_TRANSIT,
            ContainerStatus.AT_PORT,
            ContainerStatus.CUSTOMS,
            ContainerStatus.RELEASED,
            ContainerStatus.DELIVERED
    );

    private final ContainerRepository containerRepository;

    public StatusUpdateScheduler(ContainerRepository containerRepository) {
        this.containerRepository = containerRepository;
    }

    /**
     * Periodic task for updating container statuses.
     *
     * Schedule:
     * – fixedDelayString reads the interval from application.yaml (key: port.scheduler.interval-ms)
     * – Default value: 300 000 ms = 5 minutes
     * – fixedDelay guarantees the next run starts no earlier than the current one finishes
     *   (unlike fixedRate, which fires every N ms regardless of execution duration)
     *
     * Process:
     * 1. Fetch all containers from the DB
     * 2. Shuffle them (Collections.shuffle) for random selection
     * 3. Select 1–2 random containers
     * 4. For each: advance to the next status, save to the DB
     * 5. Log the change
     */
    @Scheduled(fixedDelayString = "${port.scheduler.interval-ms:300000}")
    @SchedulerLock(name = "statusUpdateScheduler", lockAtLeastFor = "PT4M", lockAtMostFor = "PT6M")
    public void updateRandomContainerStatuses() {
        List<Container> all = new ArrayList<>(containerRepository.findAll(PageRequest.of(0, 1000)).getContent());
        if (all.isEmpty()) {
            return;
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Collections.shuffle(all, rng);

        int count = Math.min(rng.nextInt(2) + 1, all.size());

        List<Container> toUpdate = new ArrayList<>(count);
        for (Container container : all.subList(0, count)) {
            ContainerStatus oldStatus = container.getStatus();
            ContainerStatus newStatus = nextStatus(oldStatus);
            container.setStatus(newStatus);
            container.setUpdatedAt(LocalDateTime.now());
            toUpdate.add(container);
            log.info("Container {} status: {} → {}",
                    container.getContainerNumber(), oldStatus, newStatus);
        }
        containerRepository.saveAll(toUpdate);
    }

    /**
     * Calculates the next status in the container lifecycle.
     *
     * Logic:
     * – Find the index of the current status in STATUS_CYCLE
     * – Advance to the next: (index + 1) % SIZE
     * – The modulo (%) ensures cyclic wrapping: last status → first status
     *
     * Examples:
     * – nextStatus(IN_TRANSIT) → AT_PORT  (index 0 → 1)
     * – nextStatus(DELIVERED)  → IN_TRANSIT (index 4 → 0 via modulo)
     * – nextStatus(null)       → IN_TRANSIT (null → start of cycle)
     *
     * @param current the current container status
     * @return the next status in the cycle
     */
    ContainerStatus nextStatus(ContainerStatus current) {
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
