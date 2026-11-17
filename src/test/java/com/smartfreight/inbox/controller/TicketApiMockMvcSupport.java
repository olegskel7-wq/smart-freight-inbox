package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.DocumentService;
import com.smartfreight.inbox.service.EmailReplyService;
import com.smartfreight.inbox.service.FeedbackLoopService;
import com.smartfreight.inbox.service.KeycloakAdminService;
import com.smartfreight.inbox.service.QuoteFollowUpService;
import com.smartfreight.inbox.service.SendRateLimiter;
import com.smartfreight.inbox.service.SettingsService;
import com.smartfreight.inbox.service.SlaProperties;
import com.smartfreight.inbox.service.SlaService;
import com.smartfreight.inbox.service.TicketAuditService;
import com.smartfreight.inbox.service.TicketQueryService;
import com.smartfreight.inbox.service.TicketReplyService;
import com.smartfreight.inbox.service.TicketWorkflowService;
import com.smartfreight.inbox.webhook.WebhookEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

/**
 * Standalone MockMvc over every {@code /api/tickets} controller, wired to mocks. No security or
 * tenant filter here — those are covered by KeycloakJwtRoleConverterTest / TenantFilterTest; the
 * subclasses verify the controllers' behaviour and DTO output.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
abstract class TicketApiMockMvcSupport {

    @Mock protected EmailTicketRepository emailTicketRepository;
    @Mock protected EmailReplyService emailReplyService;
    @Mock protected TicketAuditService auditService;
    @Mock protected SendRateLimiter sendRateLimiter;
    @Mock protected QuoteFollowUpService quoteFollowUpService;
    @Mock protected TenantDocumentRepository documentRepository;
    @Mock protected FeedbackLoopService feedbackLoopService;
    @Mock protected KeycloakAdminService keycloakAdminService;
    @Mock protected SettingsService settingsService;
    @Mock protected WebhookEventPublisher webhookEventPublisher;

    protected MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        // The real services over the mocks: the controllers' behaviour is pinned end to end.
        TicketQueryService queries = new TicketQueryService(emailTicketRepository);
        DocumentService documents = new DocumentService(documentRepository);
        // Real SLA rule (no I/O): the services flag an overdue ticket when they stop its clock.
        SlaService slaService = new SlaService(settingsService, new SlaProperties(), new SimpleMeterRegistry());
        TicketWorkflowService workflow = new TicketWorkflowService(emailTicketRepository, auditService, slaService);
        TicketReplyService replyService = new TicketReplyService(
                emailTicketRepository, emailReplyService, auditService, sendRateLimiter,
                quoteFollowUpService, documents, feedbackLoopService, webhookEventPublisher, slaService);
        TicketDetailAssembler details = new TicketDetailAssembler(queries, documents, keycloakAdminService);
        TicketApiController controller = new TicketApiController(queries, workflow, details);
        TicketReplyController replies = new TicketReplyController(replyService, details);
        TicketAssignmentController assignments = new TicketAssignmentController(workflow, details);
        mockMvc = MockMvcBuilders.standaloneSetup(controller, replies, assignments)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    protected EmailTicket ticket(long id, TicketStatus status) {
        return EmailTicket.builder()
                .id(id)
                .messageId("<m" + id + "@x>")
                .senderEmail("customer@x.com")
                .subject("Subject " + id)
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
