package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

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
 * Naudoja standalone MockMvc su RequestPostProcessor,
 * kad galėtume valdyti request.getRemoteAddr() reikšmę.
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private TicketProcessingService ticketProcessingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminController controller = new AdminController(emailTicketRepository, ticketProcessingService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * Testas: užklausa iš localhost (127.0.0.1) – 200 OK, biletai pertvarkomi.
     *
     * Tikrina, kad prepareForRetry() ir process() iškviečiami,
     * o atsakas nurodo kiek biletų buvo pertvarkyta.
     */
    @Test
    void reprocessAll_fromLocalhost_returns200AndProcessesTickets() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(2);
        when(emailTicketRepository.findIdsByStatusIn(any())).thenReturn(List.of(1L, 2L));

        mockMvc.perform(post("/admin/reprocess")
                        .with(req -> { req.setRemoteAddr("127.0.0.1"); return req; }))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Reprocessed 2 tickets")));

        verify(ticketProcessingService).process(1L);
        verify(ticketProcessingService).process(2L);
    }

    /**
     * Testas: užklausa iš IPv6 loopback (::1) – turi būti leidžiama.
     *
     * IPv6 loopback yra lygiavertis IPv4 127.0.0.1 – turi praeiti patikrinimą.
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
     *
     * Administravimo galūnės turi būti nepasiekiamos iš interneto.
     * Tikrinama, kad joks biletų apdorojimas nevykdomas.
     */
    @Test
    void reprocessAll_fromExternalIp_returns403() throws Exception {
        mockMvc.perform(post("/admin/reprocess")
                        .with(req -> { req.setRemoteAddr("203.0.113.42"); return req; }))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Forbidden")));

        verifyNoInteractions(ticketProcessingService);
        verifyNoInteractions(emailTicketRepository);
    }

    /**
     * Testas: nėra biletų pertvarkyti – 200 su informatyviu pranešimu.
     *
     * FAILED biletai buvo atstatyti į NEW (reset > 0), bet
     * findIdsByStatusIn grąžina tuščią sąrašą – todėl process() nekviečiamas.
     */
    @Test
    void reprocessAll_noTicketsToProcess_returns200WithNoTicketsMessage() throws Exception {
        when(ticketProcessingService.prepareForRetry()).thenReturn(3);
        when(emailTicketRepository.findIdsByStatusIn(any())).thenReturn(List.of());

        mockMvc.perform(post("/admin/reprocess")
                        .with(req -> { req.setRemoteAddr("127.0.0.1"); return req; }))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No tickets to reprocess")));

        verify(ticketProcessingService, never()).process(any());
    }

    /**
     * Testas: prepareForRetry() iškviečiamas prieš findIdsByStatusIn().
     *
     * FAILED → NEW atstatymas turi įvykti PRIEŠ biletų sąrašo gavimą,
     * kitaip ką tik atstatyti biletai nepatektų į sąrašą.
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
