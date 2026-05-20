package com.smartfreight.port_service.scheduler;

import com.smartfreight.port_service.entity.Container;
import com.smartfreight.port_service.entity.ContainerStatus;
import com.smartfreight.port_service.repository.ContainerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StatusUpdateScheduler} – verifies the lifecycle and update logic.
 *
 * Scenarios:
 * – All STATUS_CYCLE transitions (each status → next)
 * – Cyclic wrap: DELIVERED → IN_TRANSIT
 * – Null status → IN_TRANSIT (recovery)
 * – Empty container list — nothing is saved
 * – On update: container is saved with the new status and a non-null updatedAt timestamp
 * – At most 2 containers are updated per run
 * – A single container is always updated (no randomness issues with count 1)
 */
@ExtendWith(MockitoExtension.class)
class StatusUpdateSchedulerTest {

    @Mock
    private ContainerRepository containerRepository;

    @InjectMocks
    private StatusUpdateScheduler scheduler;

    private Container buildContainer(String number, ContainerStatus status) {
        Container c = new Container();
        c.setContainerNumber(number);
        c.setStatus(status);
        return c;
    }

    // -------------------------------------------------------------------------
    // nextStatus – lifecycle logic
    // -------------------------------------------------------------------------

    @Test
    void nextStatus_inTransit_returnsAtPort() {
        assertThat(scheduler.nextStatus(ContainerStatus.IN_TRANSIT))
                .isEqualTo(ContainerStatus.AT_PORT);
    }

    @Test
    void nextStatus_atPort_returnsCustoms() {
        assertThat(scheduler.nextStatus(ContainerStatus.AT_PORT))
                .isEqualTo(ContainerStatus.CUSTOMS);
    }

    @Test
    void nextStatus_customs_returnsReleased() {
        assertThat(scheduler.nextStatus(ContainerStatus.CUSTOMS))
                .isEqualTo(ContainerStatus.RELEASED);
    }

    @Test
    void nextStatus_released_returnsDelivered() {
        assertThat(scheduler.nextStatus(ContainerStatus.RELEASED))
                .isEqualTo(ContainerStatus.DELIVERED);
    }

    @Test
    void nextStatus_delivered_cyclesBackToInTransit() {
        assertThat(scheduler.nextStatus(ContainerStatus.DELIVERED))
                .isEqualTo(ContainerStatus.IN_TRANSIT);
    }

    @Test
    void nextStatus_nullStatus_returnsInTransit() {
        assertThat(scheduler.nextStatus(null)).isEqualTo(ContainerStatus.IN_TRANSIT);
    }

    // -------------------------------------------------------------------------
    // updateRandomContainerStatuses – update logic
    // -------------------------------------------------------------------------

    @Test
    void updateRandomContainerStatuses_emptyList_doesNotSaveAll() {
        when(containerRepository.findAll()).thenReturn(Collections.emptyList());

        scheduler.updateRandomContainerStatuses();

        verify(containerRepository, never()).saveAll(any());
    }

    @Test
    void updateRandomContainerStatuses_withContainers_callsSaveAll() {
        List<Container> containers = List.of(
                buildContainer("MSCU1111111", ContainerStatus.IN_TRANSIT),
                buildContainer("OOLU2222222", ContainerStatus.AT_PORT),
                buildContainer("HLXU3333333", ContainerStatus.CUSTOMS)
        );
        when(containerRepository.findAll()).thenReturn(containers);

        scheduler.updateRandomContainerStatuses();

        verify(containerRepository, times(1)).saveAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateRandomContainerStatuses_withContainers_savesAtMostTwo() {
        List<Container> containers = List.of(
                buildContainer("MSCU1111111", ContainerStatus.IN_TRANSIT),
                buildContainer("OOLU2222222", ContainerStatus.AT_PORT),
                buildContainer("HLXU3333333", ContainerStatus.CUSTOMS),
                buildContainer("TCKU4444444", ContainerStatus.RELEASED)
        );
        when(containerRepository.findAll()).thenReturn(containers);

        scheduler.updateRandomContainerStatuses();

        ArgumentCaptor<List<Container>> captor = ArgumentCaptor.forClass(List.class);
        verify(containerRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateRandomContainerStatuses_savedContainer_hasUpdatedStatusAndTimestamp() {
        Container container = buildContainer("MSCU1234567", ContainerStatus.IN_TRANSIT);
        when(containerRepository.findAll()).thenReturn(List.of(container));

        scheduler.updateRandomContainerStatuses();

        ArgumentCaptor<List<Container>> captor = ArgumentCaptor.forClass(List.class);
        verify(containerRepository).saveAll(captor.capture());
        Container saved = captor.getValue().get(0);
        assertThat(saved.getStatus()).isEqualTo(ContainerStatus.AT_PORT);
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateRandomContainerStatuses_singleContainer_alwaysUpdatesIt() {
        Container container = buildContainer("MSCU9999999", ContainerStatus.CUSTOMS);
        when(containerRepository.findAll()).thenReturn(List.of(container));

        scheduler.updateRandomContainerStatuses();

        ArgumentCaptor<List<Container>> captor = ArgumentCaptor.forClass(List.class);
        verify(containerRepository, times(1)).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(container.getStatus()).isEqualTo(ContainerStatus.RELEASED);
    }
}
