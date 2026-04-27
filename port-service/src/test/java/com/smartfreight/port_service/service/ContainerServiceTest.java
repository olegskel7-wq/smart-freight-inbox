package com.smartfreight.port_service.service;

import com.smartfreight.port_service.dto.ContainerResponse;
import com.smartfreight.port_service.entity.Container;
import com.smartfreight.port_service.repository.ContainerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Paslaugos vienetų testai – tikrina verslo logiką be Spring konteksto.
 *
 * Testuojami scenarijai:
 * – Rastas konteineris grąžina DTO su teisingais laukais
 * – Nerastas konteineris grąžina Optional.empty()
 * – Null įvestis grąžina Optional.empty() ir nekviečia saugyklos
 * – Tuščia eilutė grąžina Optional.empty() ir nekviečia saugyklos
 * – Mišraus registro numeris normalizuojamas prieš paiešką DB
 */
@ExtendWith(MockitoExtension.class)
class ContainerServiceTest {

    @Mock
    private ContainerRepository containerRepository;

    @InjectMocks
    private ContainerService containerService;

    private Container buildContainer(String number, String status) {
        return Container.builder()
                .id(1L)
                .containerNumber(number)
                .status(status)
                .location("Felixstowe Terminal 2")
                .eta(LocalDateTime.of(2026, 4, 25, 14, 30))
                .updatedAt(LocalDateTime.of(2026, 4, 20, 10, 15))
                .build();
    }

    @Test
    void findByNumber_existingContainer_returnsDtoWithCorrectFields() {
        Container entity = buildContainer("MSCU1234567", "AT_PORT");
        when(containerRepository.findByContainerNumber("MSCU1234567"))
                .thenReturn(Optional.of(entity));

        Optional<ContainerResponse> result = containerService.findByNumber("MSCU1234567");

        assertThat(result).isPresent();
        ContainerResponse dto = result.get();
        assertThat(dto.containerNumber()).isEqualTo("MSCU1234567");
        assertThat(dto.status()).isEqualTo("AT_PORT");
        assertThat(dto.location()).isEqualTo("Felixstowe Terminal 2");
        assertThat(dto.eta()).isEqualTo(LocalDateTime.of(2026, 4, 25, 14, 30));
    }

    @Test
    void findByNumber_nonExistingContainer_returnsEmpty() {
        when(containerRepository.findByContainerNumber("XXXX0000000"))
                .thenReturn(Optional.empty());

        Optional<ContainerResponse> result = containerService.findByNumber("XXXX0000000");

        assertThat(result).isEmpty();
    }

    @Test
    void findByNumber_nullInput_returnsEmptyWithoutCallingRepository() {
        Optional<ContainerResponse> result = containerService.findByNumber(null);

        assertThat(result).isEmpty();
        verifyNoInteractions(containerRepository);
    }

    @Test
    void findByNumber_blankInput_returnsEmptyWithoutCallingRepository() {
        Optional<ContainerResponse> result = containerService.findByNumber("   ");

        assertThat(result).isEmpty();
        verifyNoInteractions(containerRepository);
    }

    @Test
    void findByNumber_lowercaseInput_normalizesToUppercaseBeforeSearch() {
        Container entity = buildContainer("MSCU1234567", "IN_TRANSIT");
        when(containerRepository.findByContainerNumber("MSCU1234567"))
                .thenReturn(Optional.of(entity));

        Optional<ContainerResponse> result = containerService.findByNumber("mscu1234567");

        assertThat(result).isPresent();
        verify(containerRepository).findByContainerNumber("MSCU1234567");
    }

    @Test
    void findByNumber_inputWithSpaces_trimmedAndNormalized() {
        Container entity = buildContainer("OOLU9876543", "CUSTOMS");
        when(containerRepository.findByContainerNumber("OOLU9876543"))
                .thenReturn(Optional.of(entity));

        Optional<ContainerResponse> result = containerService.findByNumber("  oolu9876543  ");

        assertThat(result).isPresent();
        verify(containerRepository).findByContainerNumber("OOLU9876543");
    }
}
