package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.SettingsResponse;
import com.smartfreight.inbox.service.MailboxManagementService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant's mailboxes on the {@code /settings} page. The reconciler picks up every change within one
 * pass (~30 s), no restart. A rejected change answers {@code 400}/{@code 404}/{@code 409} with a plain
 * message; a successful one returns the refreshed {@link SettingsResponse}, except delete ({@code 204}).
 */
@RestController
@RequestMapping("/api/settings/mailboxes")
public class MailboxSettingsController {

    private final MailboxManagementService mailboxService;
    private final SettingsSnapshotAssembler snapshotAssembler;

    public MailboxSettingsController(MailboxManagementService mailboxService,
                                     SettingsSnapshotAssembler snapshotAssembler) {
        this.mailboxService = mailboxService;
        this.snapshotAssembler = snapshotAssembler;
    }

    /**
     * Request body for {@code POST /api/settings/mailboxes} and {@code PUT /api/settings/mailboxes/{id}}
     * (on PUT a blank password keeps the stored one).
     */
    public record MailboxRequest(String host, Integer port, String username, String password,
                                 String smtpHost, Integer smtpPort, String fromAddress) {}

    /** Request body for {@code POST /api/settings/mailboxes/{id}/toggle}. */
    public record ToggleRequest(boolean enabled) {}

    /** POST /api/settings/mailboxes — adds a mailbox for the current tenant. */
    @PostMapping
    public SettingsResponse addMailbox(@RequestBody MailboxRequest request) {
        try {
            mailboxService.create(request.host(), request.port(), request.username(), request.password(),
                    request.smtpHost(), request.smtpPort(), request.fromAddress());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This mailbox (host + username) is already configured.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return snapshotAssembler.snapshot();
    }

    /**
     * PUT /api/settings/mailboxes/{id} — edits a mailbox in place: new password, server or SMTP
     * settings without delete + re-add, so open tickets keep their {@code mailbox_id} and keep replying
     * through it. Unknown/foreign id → 404, invalid input or a re-point without the password → 400, a
     * (host, username) clash with another mailbox or renaming the account of a mailbox that has received
     * mail → 409.
     */
    @PutMapping("/{id}")
    public SettingsResponse updateMailbox(@PathVariable Long id, @RequestBody MailboxRequest request) {
        try {
            mailboxService.update(id, request.host(), request.port(), request.username(), request.password(),
                    request.smtpHost(), request.smtpPort(), request.fromAddress());
        } catch (MailboxManagementService.MailboxNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (MailboxManagementService.MailboxInUseException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another mailbox with this host + username is already configured.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return snapshotAssembler.snapshot();
    }

    /** POST /api/settings/mailboxes/{id}/toggle — pauses/resumes a mailbox without losing its credentials. */
    @PostMapping("/{id}/toggle")
    public SettingsResponse toggleMailbox(@PathVariable Long id, @RequestBody ToggleRequest request) {
        try {
            mailboxService.setEnabled(id, request.enabled());
        } catch (MailboxManagementService.MailboxNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return snapshotAssembler.snapshot();
    }

    /** DELETE /api/settings/mailboxes/{id} — removes a mailbox; its adapter stops on the next pass. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMailbox(@PathVariable Long id) {
        try {
            mailboxService.delete(id);
        } catch (MailboxManagementService.MailboxNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }
}
