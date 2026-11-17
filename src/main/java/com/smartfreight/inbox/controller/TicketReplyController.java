package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.TicketDetailDto;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.service.ReplySignatureService;
import com.smartfreight.inbox.service.TicketReplyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The reply to a ticket: the operator edits, saves, closes or sends the draft and picks the
 * on-file document that goes out with it. The orchestration lives in {@link TicketReplyService};
 * this controller maps its outcomes to HTTP.
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketReplyController {

    private final TicketReplyService replies;
    private final TicketDetailAssembler details;

    public TicketReplyController(TicketReplyService replies, TicketDetailAssembler details) {
        this.replies = replies;
        this.details = details;
    }

    /** Request body for {@code POST /api/tickets/{id}/draft}: action = save | close | send. */
    public record DraftRequest(String action, String draftReply) {}

    /** Request body for {@code POST /api/tickets/{id}/attach-document}: null id detaches. */
    public record AttachRequest(Long documentId) {}

    /**
     * POST /api/tickets/{id}/draft — save / close / send the draft. Returns the updated detail;
     * 429 when send is rate-limited, 502 when SMTP fails. CLOSED/AUTO_SENT are terminal →
     * unchanged detail.
     */
    @Transactional
    @PostMapping("/{id}/draft")
    public ResponseEntity<TicketDetailDto> updateDraft(@PathVariable Long id,
                                                       @RequestBody DraftRequest request,
                                                       @AuthenticationPrincipal Jwt jwt) {
        EmailTicket ticket = details.requireTicket(id);
        // Signed as the sending operator (drafts are unsigned — see ReplySignatureService).
        String signedAs = ReplySignatureService.displayName(jwt);
        return switch (replies.applyDraft(ticket, request.action(), request.draftReply(), signedAs)) {
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
            case SEND_FAILED -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            case TERMINAL, DONE -> ResponseEntity.ok(details.buildDetail(ticket));
        };
    }

    /**
     * POST /api/tickets/{id}/attach-document — attach an on-file document to the reply, or detach
     * when {@code documentId} is null. Terminal tickets → unchanged detail; an unknown document → 404.
     */
    @Transactional
    @PostMapping("/{id}/attach-document")
    public TicketDetailDto attachDocument(@PathVariable Long id, @RequestBody AttachRequest request) {
        EmailTicket ticket = details.requireTicket(id);
        if (replies.attachDocument(ticket, request.documentId())
                == TicketReplyService.AttachOutcome.DOCUMENT_NOT_FOUND) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + request.documentId());
        }
        return details.buildDetail(ticket);
    }
}
