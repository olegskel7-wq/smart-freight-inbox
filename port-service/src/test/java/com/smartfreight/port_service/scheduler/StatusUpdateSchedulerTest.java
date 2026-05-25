package com.smartfreight.port_service.scheduler;

import com.smartfreight.port_service.entity.Container;
import com.smartfreight.port_service.entity.ContainerStatus;
import com.smartfreight.port_service.repository.ContainerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StatusUpdateScheduler — verify status lifecycle and update logic.
 *
 * Covered scenarios:
 * – All STATUS_CYCLE transitions (each status → next)
 * – Cyclic wrap-around: DELIVERED → IN_TRANSIT
 * – Null status → IN_TRANSIT (recovery)
 * – Empty container list — saveAll is never called
 * – Updated container is persisted with new status and updatedAt timestamp
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
    // nextStatus – логика жизненного цикла
    // -------------------------------------------------------------------------

    @Test
    void nextStatus_inTransit_returnsAtPort() {
        assertThat(scheduler.nextStatus(ContainerStatus.IN_TRANSIT)).isEqualTo(ContainerStatus.AT_PORT);
    }

    @Test
    void nextStatus_atPort_returnsCustoms() {
        assertThat(scheduler.nextStatus(ContainerStatus.AT_PORT)).isEqualTo(ContainerStatus.CUSTOMS);
    }

    @Test
    void nextStatus_customs_returnsReleased() {
        assertThat(scheduler.nextStatus(ContainerStatus.CUSTOMS)).isEqualTo(ContainerStatus.RELEASED);
    }

    @Test
    void nextStatus_released_returnsDelivered() {
        assertThat(scheduler.nextStatus(ContainerStatus.RELEASED)).isEqualTo(ContainerStatus.DELIVERED);
    }

    @Test
    void nextStatus_delivered_cyclesBackToInTransit() {
        assertThat(scheduler.nextStatus(ContainerStatus.DELIVERED)).isEqualTo(ContainerStatus.IN_TRANSIT);
    }

    @Test
    void nextStatus_nullStatus_returnsInTransit() {
        assertThat(scheduler.nextStatus(null)).isEqualTo(ContainerStatus.IN_TRANSIT);
    }

    // -------------------------------------------------------------------------
    // updateRandomContainerStatuses – логика обновления
    // -------------------------------------------------------------------------

    @Test
    void updateRandomContainerStatuses_emptyList_doesNotSave() {
        when(containerRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(Collections.emptyList()));

        scheduler.updateRandomContainerStatuses();

        verify(containerRepository, never()).saveAll(anyList());
    }

    @Test
    void updateRandomContainerStatuses_withContainers_savesAtLeastOne() {
        List<Container> containers = List.of(
                buildContainer("MSCU1111111", ContainerStatus.IN_TRANSIT),
                buildContainer("OOLU2222222", ContainerStatus.AT_PORT),
                buildContainer("HLXU3333333", ContainerStatus.CUSTOMS)
        );
        when(containerRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(containers));

        scheduler.updateRandomContainerStatuses();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Container>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(containerRepository, times(1)).saveAll(captor.capture());
        assertThat(captor.getValue()).isNotEmpty();
    }

    @Test
    void updateRandomContainerStatuses_withContainers_savesAtMostTwo() {
        List<Container> containers = List.of(
                buildContainer("MSCU1111111", ContainerStatus.IN_TRANSIT),
                buildContainer("OOLU2222222", ContainerStatus.AT_PORT),
                buildContainer("HLXU3333333", ContainerStatus.CUSTOMS),
                buildContainer("TCKU4444444", ContainerStatus.RELEASED)
        );
        when(containerRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(containers));

        scheduler.updateRandomContainerStatuses();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Container>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(containerRepository, times(1)).saveAll(captor.capture());
        // Не более 2 контейнеров обновляется за один запуск
        assertThat(captor.getValue()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void updateRandomContainerStatuses_savedContainer_hasUpdatedStatusAndTimestamp() {
        Container container = buildContainer("MSCU1234567", ContainerStatus.IN_TRANSIT);
        when(containerRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(container)));

        scheduler.updateRandomContainerStatuses();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Container>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(containerRepository).saveAll(captor.capture());
        Container saved = captor.getValue().get(0);
        assertThat(saved.getStatus()).isEqualTo(ContainerStatus.AT_PORT);
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void updateRandomContainerStatuses_singleContainer_alwaysUpdatesIt() {
        Container container = buildContainer("MSCU9999999", ContainerStatus.CUSTOMS);
        when(containerRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(container)));

        scheduler.updateRandomContainerStatuses();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Container>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(containerRepository, times(1)).saveAll(captor.capture());
        // При одном контейнере он всегда должен быть обновлён
        assertThat(captor.getValue()).containsExactly(container);
        assertThat(container.getStatus()).isEqualTo(ContainerStatus.RELEASED);
    }
}
