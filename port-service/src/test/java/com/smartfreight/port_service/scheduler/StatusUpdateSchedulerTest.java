package com.smartfreight.port_service.scheduler;

import com.smartfreight.port_service.entity.Container;
import com.smartfreight.port_service.repository.ContainerRepository;
import org.junit.jupiter.api.BeforeEach;
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
 * Planuotojo vienetų testai – tikrina būsenų gyvavimo ciklą ir atnaujinimo logiką.
 *
 * Testuojami scenarijai:
 * – Visi STATUS_CYCLE perėjimai (kiekvienas → kitas)
 * – Ciklinis perėjimas: DELIVERED → IN_TRANSIT
 * – Nežinoma būsena → IN_TRANSIT (atkūrimas)
 * – Tuščias konteinerių sąrašas – niekas nekviečiama
 * – Atnaujinimo metu konteineris išsaugomas su nauja būsena ir updatedAt laiku
 */
@ExtendWith(MockitoExtension.class)
class StatusUpdateSchedulerTest {

    @Mock
    private ContainerRepository containerRepository;

    @InjectMocks
    private StatusUpdateScheduler scheduler;

    private Container buildContainer(String number, String status) {
        Container c = new Container();
        c.setContainerNumber(number);
        c.setStatus(status);
        return c;
    }

    // -------------------------------------------------------------------------
    // nextStatus – gyvavimo ciklo logika
    // -------------------------------------------------------------------------

    @Test
    void nextStatus_inTransit_returnsAtPort() {
        assertThat(scheduler.nextStatus("IN_TRANSIT")).isEqualTo("AT_PORT");
    }

    @Test
    void nextStatus_atPort_returnsCustoms() {
        assertThat(scheduler.nextStatus("AT_PORT")).isEqualTo("CUSTOMS");
    }

    @Test
    void nextStatus_customs_returnsReleased() {
        assertThat(scheduler.nextStatus("CUSTOMS")).isEqualTo("RELEASED");
    }

    @Test
    void nextStatus_released_returnsDelivered() {
        assertThat(scheduler.nextStatus("RELEASED")).isEqualTo("DELIVERED");
    }

    @Test
    void nextStatus_delivered_cyclesBackToInTransit() {
        assertThat(scheduler.nextStatus("DELIVERED")).isEqualTo("IN_TRANSIT");
    }

    @Test
    void nextStatus_unknownStatus_returnsInTransit() {
        assertThat(scheduler.nextStatus("UNKNOWN_XYZ")).isEqualTo("IN_TRANSIT");
    }

    @Test
    void nextStatus_nullStatus_returnsInTransit() {
        assertThat(scheduler.nextStatus(null)).isEqualTo("IN_TRANSIT");
    }

    // -------------------------------------------------------------------------
    // updateRandomContainerStatuses – atnaujinimo logika
    // -------------------------------------------------------------------------

    @Test
    void updateRandomContainerStatuses_emptyList_doesNotSave() {
        when(containerRepository.findAll()).thenReturn(Collections.emptyList());

        scheduler.updateRandomContainerStatuses();

        verify(containerRepository, never()).save(any());
    }

    @Test
    void updateRandomContainerStatuses_withContainers_savesAtLeastOne() {
        List<Container> containers = List.of(
                buildContainer("MSCU1111111", "IN_TRANSIT"),
                buildContainer("OOLU2222222", "AT_PORT"),
                buildContainer("HLXU3333333", "CUSTOMS")
        );
        when(containerRepository.findAll()).thenReturn(containers);

        scheduler.updateRandomContainerStatuses();

        // Saugykla turi būti iškviesta bent vieną kartą
        verify(containerRepository, atLeastOnce()).save(any(Container.class));
    }

    @Test
    void updateRandomContainerStatuses_withContainers_savesAtMostTwo() {
        List<Container> containers = List.of(
                buildContainer("MSCU1111111", "IN_TRANSIT"),
                buildContainer("OOLU2222222", "AT_PORT"),
                buildContainer("HLXU3333333", "CUSTOMS"),
                buildContainer("TCKU4444444", "RELEASED")
        );
        when(containerRepository.findAll()).thenReturn(containers);

        scheduler.updateRandomContainerStatuses();

        // Ne daugiau nei 2 konteineriai atnaujinami per vieną paleidimą
        verify(containerRepository, atMost(2)).save(any(Container.class));
    }

    @Test
    void updateRandomContainerStatuses_savedContainer_hasUpdatedStatusAndTimestamp() {
        Container container = buildContainer("MSCU1234567", "IN_TRANSIT");
        when(containerRepository.findAll()).thenReturn(List.of(container));

        ArgumentCaptor<Container> captor = ArgumentCaptor.forClass(Container.class);
        scheduler.updateRandomContainerStatuses();

        verify(containerRepository).save(captor.capture());
        Container saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("AT_PORT");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void updateRandomContainerStatuses_singleContainer_alwaysUpdatesIt() {
        Container container = buildContainer("MSCU9999999", "CUSTOMS");
        when(containerRepository.findAll()).thenReturn(List.of(container));

        scheduler.updateRandomContainerStatuses();

        // Su vienu konteineriu jis visada turi būti atnaujintas
        verify(containerRepository, times(1)).save(container);
        assertThat(container.getStatus()).isEqualTo("RELEASED");
    }
}
