package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4: test autoconfigurations moved into per-module artifacts (spring-boot-webmvc-test)
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real {@code analytics.html} through Thymeleaf (Phase 2.1).
 *
 * The standalone {@link AnalyticsControllerTest} stubs the view layer, so a template
 * error — bad SpEL, a record the inline-JavaScript serializer can't handle — would only
 * surface at runtime. This test walks the full stack: Spring context, security filter
 * chain (mock operator), controller, Thymeleaf template, chart-data inlining.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AnalyticsPageRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmailTicketRepository tickets;

    @Test
    @WithMockUser(roles = "OPERATOR")
    void analyticsPage_rendersWithChartData() throws Exception {
        // A ticket with a confidence score makes both chart branches render,
        // exercising the th:inline serialization of the series lists.
        tickets.save(EmailTicket.builder()
                .messageId(UUID.randomUUID() + "@render-test")
                .senderEmail("customer@example.com")
                .subject("render test")
                .body("test")
                .status(TicketStatus.DRAFT_READY)
                .ticketType(TicketType.RFQ)
                .aiConfidence(0.7)
                .build());

        mockMvc.perform(get("/analytics"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Inbox Analytics")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("volumeChart")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("confidenceChart")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/webjars/chart.js/4.5.0/dist/chart.umd.js")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void analyticsPage_rendersOnEmptyPeriodWithoutCharts() throws Exception {
        // Other test classes COMMIT tickets into the shared container; soft-delete them
        // inside this rolled-back TX so the empty-period branch actually renders.
        tickets.deleteAll();

        mockMvc.perform(get("/analytics"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No tickets received in this period")));
    }
}
