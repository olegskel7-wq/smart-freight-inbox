package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Vienetų testai administravimo valdikliui (AdminController).
 *
 * Tikrina:
 * – localhost apsaugos mechanizmą (leisti tik iš 127.0.0.1 / ::1)
 * – biletų pertvarkymą (reprocess)
 * – atsako pranešimus įvairiomis sąlygomis
 *
 * TaskExecutor stubbinamas sinchroniškai (doAnswer) kad process() kvietimai
 * įvyktų iš karto ir galėtume juos patikrinti su verify().
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminControllerTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private TicketProcessingService ticketProcessingService;

    @Mock
    private TaskExecutor taskExecutor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Run submitted Runnables immediately so process() calls are synchronous in tests
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(taskExecutor).execute(any());

        AdminController controller = new AdminController(
                emailTicketRepository, ticketProcessingService, taskExecutor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * Testas: užklausa iš localhost (127.0.0.1) – 200 OK, biletai pertvarkomi.
     */
    @Test
    void reprocessAll_fromLocalhost_returns200AndProcessesTickets() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(2);
        when(emailTicketRepository.findIdsByStatusIn(any())).thenReturn(List.of(1L, 2L));

        mockMvc.perform(post("/admin/reprocess")
                        .with(req -> { req.setRemoteAddr("127.0.0.1"); return req; }))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2 tickets")));

        verify(ticketProcessingService).process(1L);
        verify(ticketProcessingService).process(2L);
    }

    /**
     * Testas: užklausa iš IPv6 loopback (::1) – turi būti leidžiama.
     */
    @Test
    void reprocessAll_fromIPv6Localhost_returns200() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(0);
        when(emailTicketRepository.findIdsByStatusIn(any())).thenReturn(List.of());

        mockMvc.perform(post("/admin/reprocess")
                        .with(req -> { req.setRemoteAddr("::1"); return req; }))
                .andExpect(status().isOk());
    }

    /**
     * Testas: užklausa iš išorinio IP – 403 Forbidden.
     */
    @Test
    void reprocessAll_fromExternalIp_returns403() throws Exception {
        mockMvc.perform(post("/admin/reprocess")
                        .with(req -> { req.setRemoteAddr("203.0.113.42"); return req; }))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Forbidden")));

        verifyNoInteractions(ticketProcessingService);
        verifyNoInteractions(emailTicketRepository);
    }

    /**
     * Testas: nėra biletų pertvarkyti – 200 su informatyviu pranešimu.
     */
    @Test
    void reprocessAll_noTicketsToProcess_returns200WithNoTicketsMessage() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(3);
        when(emailTicketRepository.findIdsByStatusIn(any())).thenReturn(List.of());

        mockMvc.perform(post("/admin/reprocess")
                        .with(req -> { req.setRemoteAddr("127.0.0.1"); return req; }))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No tickets to reprocess")));

        verify(ticketProcessingService, never()).process(any());
    }

    /**
     * Testas: prepareForRetry() iškviečiamas prieš findIdsByStatusIn().
     */
    @Test
    void reprocessAll_callsPrepareForRetryBeforeFindIds() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(0);
        when(emailTicketRepository.findIdsByStatusIn(
                List.of(TicketStatus.NEW, TicketStatus.RETRY_LATER))).thenReturn(List.of());

        mockMvc.perform(post("/admin/reprocess")
                        .with(req -> { req.setRemoteAddr("127.0.0.1"); return req; }));

        var inOrder = inOrder(ticketProcessingService, emailTicketRepository);
        inOrder.verify(ticketProcessingService).prepareForRetry();
        inOrder.verify(emailTicketRepository).findIdsByStatusIn(any());
    }
}
