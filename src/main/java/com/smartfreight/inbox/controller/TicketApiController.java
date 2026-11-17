package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.PageDto;
import com.smartfreight.inbox.controller.dto.TicketDetailDto;
import com.smartfreight.inbox.controller.dto.TicketStatsDto;
import com.smartfreight.inbox.controller.dto.TicketSummaryDto;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.service.TicketQueryService;
import com.smartfreight.inbox.service.TicketQueryService.TicketCounts;
import com.smartfreight.inbox.service.TicketWorkflowService;
import com.smartfreight.inbox.tenancy.TenantClaims;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * JSON API backing the React {@code /tickets} pages. It speaks DTOs over
 * {@code /api/tickets} and is secured by the stateless resource-server chain (bearer JWT).
 * This controller serves the list, the summary strip and the ticket itself (detail, close, delete);
 * the reply lives in {@link TicketReplyController}, ownership in {@link TicketAssignmentController}.
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketApiController {

    /** Columns the list may sort by — anything else would fail as a PropertyReferenceException. */
    private static final Set<String> SORTABLE_COLUMNS = Set.of(
            "id", "senderEmail", "subject", "extractedIdentifier",
            "status", "ticketType", "slaDeadlineAt", "aiConfidence", "createdAt", "updatedAt"
    );
    private static final String DEFAULT_SORT = "createdAt";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Escalated tickets (negative tone or HIGH urgency) float to the top of the default
     * view. Composed AHEAD of the createdAt sort so the most pressing work is seen first; applied only
     * when the operator hasn't picked an explicit sort column (then their choice wins). urgency is an
     * ORDINAL column (HIGH=2) so {@code DESC} ranks it correctly as a plain column.
     */
    private static final Sort ESCALATION_SORT = Sort.by(
            Sort.Order.desc("sentimentNegative"),
            Sort.Order.desc("urgency"));

    private final TicketQueryService ticketQueries;
    private final TicketWorkflowService workflow;
    private final TicketDetailAssembler details;

    public TicketApiController(TicketQueryService ticketQueries,
                               TicketWorkflowService workflow,
                               TicketDetailAssembler details) {
        this.ticketQueries = ticketQueries;
        this.workflow = workflow;
        this.details = details;
    }

    /**
     * GET /api/tickets — paginated, sorted, optionally status-filtered list. The {@code assignee}
     * filter scopes the shared queue: {@code mine} = the caller's tickets,
     * {@code unassigned} = unclaimed work, {@code all} (default) = everything.
     */
    @GetMapping
    public PageDto<TicketSummaryDto> list(
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "dir", defaultValue = "desc") String dir,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "assignee", defaultValue = "all") String assignee,
            @RequestParam(name = "view", required = false) String view,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @AuthenticationPrincipal Jwt jwt) {

        // An explicit, valid sort column means the operator picked it (the SPA omits `sort` otherwise);
        // an absent/invalid value is the default view. Only the default view floats escalated tickets up,
        // so an explicit Date sort really wins — even when it is createdAt itself.
        boolean explicitSort = sort != null && SORTABLE_COLUMNS.contains(sort);
        String safeSort = explicitSort ? sort : DEFAULT_SORT;
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        Sort sortSpec = Sort.by(direction, safeSort);
        if (!explicitSort) {
            sortSpec = ESCALATION_SORT.and(sortSpec);
        }
        Pageable pageable = PageRequest.of(safePage, safeSize, sortSpec);
        // A summary-chip `view` takes precedence over the status/assignee filters (see the service).
        String me = jwt == null ? null : jwt.getSubject();
        Page<EmailTicket> ticketPage = ticketQueries.list(view, status, assignee, me, pageable);

        Map<String, String> emails = details.assigneeEmails(ticketPage.getContent(), TenantClaims.fromJwt(jwt));
        return PageDto.of(ticketPage, t -> TicketSummaryDto.from(t,
                t.getAssigneeUserId() == null ? null : emails.get(t.getAssigneeUserId())));
    }

    /**
     * GET /api/tickets/summary — at-a-glance counts for the dashboard strip. Computed
     * on request and tenant-scoped (every count is a {@code @TenantId} derived query). The literal
     * {@code /summary} path is matched ahead of {@code /{id}}, so it never collides with detail.
     */
    @GetMapping("/summary")
    public TicketStatsDto summary() {
        TicketCounts counts = ticketQueries.counts();
        return new TicketStatsDto(counts.open(), counts.awaitingReview(), counts.slaBreached(),
                counts.autoSentToday());
    }

    /** GET /api/tickets/{id} — full ticket detail (404 if missing). */
    @GetMapping("/{id}")
    public TicketDetailDto detail(@PathVariable Long id) {
        return details.buildDetail(details.requireTicket(id));
    }

    /** POST /api/tickets/{id}/close — close the ticket (AUTO_SENT is terminal → no-op). */
    @Transactional
    @PostMapping("/{id}/close")
    public TicketDetailDto close(@PathVariable Long id) {
        EmailTicket ticket = details.requireTicket(id);
        workflow.close(ticket);
        return details.buildDetail(ticket);
    }

    /** DELETE /api/tickets/{id} — soft delete (409 while PROCESSING). */
    @Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        EmailTicket ticket = details.requireTicket(id);
        return workflow.delete(ticket)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
