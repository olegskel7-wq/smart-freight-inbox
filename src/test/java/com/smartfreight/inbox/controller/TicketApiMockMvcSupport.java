package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.EmailReplyService;
import com.smartfreight.inbox.service.FeedbackLoopService;
import com.smartfreight.inbox.service.KeycloakAdminService;
import com.smartfreight.inbox.service.QuoteFollowUpService;
import com.smartfreight.inbox.service.SendRateLimiter;
import com.smartfreight.inbox.service.TicketAuditService;
import com.smartfreight.inbox.webhook.WebhookEventPublisher;
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
    @Mock protected WebhookEventPublisher webhookEventPublisher;

    protected MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        TicketDetailAssembler details =
                new TicketDetailAssembler(emailTicketRepository, documentRepository, keycloakAdminService);
        TicketApiController controller = new TicketApiController(emailTicketRepository, auditService, details);
        TicketReplyController replies = new TicketReplyController(
                emailTicketRepository, emailReplyService, auditService, sendRateLimiter,
                quoteFollowUpService, documentRepository, feedbackLoopService, webhookEventPublisher, details);
        TicketAssignmentController assignments =
                new TicketAssignmentController(emailTicketRepository, auditService, details);
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
