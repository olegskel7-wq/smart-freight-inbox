package com.smartfreight.port_service.controller;

import com.smartfreight.port_service.dto.ContainerResponse;
import com.smartfreight.port_service.service.ContainerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for ContainerController — verify HTTP routing and response formatting.
 *
 * Uses standalone MockMvc (no Spring context), so tests run very fast.
 * ContainerService is mocked via Mockito.
 *
 * Covered scenarios:
 * – 200 OK with JSON body when container is found
 * – 404 Not Found when container does not exist
 */
@ExtendWith(MockitoExtension.class)
class ContainerControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ContainerService containerService;

    @InjectMocks
    private ContainerController containerController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(containerController).build();
    }

    @Test
    void getByNumber_existingContainer_returns200WithJson() throws Exception {
        ContainerResponse dto = new ContainerResponse(
                "MSCU1234567",
                "AT_PORT",
                "Felixstowe Terminal 2",
                LocalDateTime.of(2026, 4, 25, 14, 30),
                LocalDateTime.of(2026, 4, 20, 10, 15)
        );
        when(containerService.findByNumber("MSCU1234567")).thenReturn(Optional.of(dto));

        mockMvc.perform(get("/api/v1/containers/MSCU1234567"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.containerNumber").value("MSCU1234567"))
                .andExpect(jsonPath("$.status").value("AT_PORT"))
                .andExpect(jsonPath("$.location").value("Felixstowe Terminal 2"));
    }

    @Test
    void getByNumber_nonExistingContainer_returns404() throws Exception {
        when(containerService.findByNumber("XXXX0000000")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/containers/XXXX0000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByNumber_serviceReturnsEmpty_returns404() throws Exception {
        when(containerService.findByNumber("HLXU9999999")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/containers/HLXU9999999"))
                .andExpect(status().isNotFound());
    }
}
