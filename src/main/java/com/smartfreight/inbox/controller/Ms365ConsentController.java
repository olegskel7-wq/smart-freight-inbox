package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.TenantMs365Directory;
import com.smartfreight.inbox.service.Ms365ConsentService;
import com.smartfreight.inbox.service.Ms365ConsentService.ConsentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Microsoft 365 connection (V38) — see {@link Ms365ConsentService} for why it is a flow.
 *
 * <p>{@code /api/settings/ms365/**} is the tenant ADMIN's side (the API chain requires ADMIN).
 * {@code /ms365/consent/callback} is where Microsoft sends the customer's IT administrator back; that
 * person has no SmartFreight login, so it is public (static chain) and answers with a small
 * self-contained page instead of the SPA. Its trust comes from the single-use {@code state} and from
 * the code redemption, never from the query parameters themselves.
 */
@RestController
public class Ms365ConsentController {

    private static final Logger log = LoggerFactory.getLogger(Ms365ConsentController.class);

    private final Ms365ConsentService consent;

    public Ms365ConsentController(Ms365ConsentService consent) {
        this.consent = consent;
    }

    public record DirectoryDto(String directoryId, String boundBy, LocalDateTime boundAt) {
        static DirectoryDto of(TenantMs365Directory d) {
            return new DirectoryDto(d.getDirectoryId(), d.getBoundBy(), d.getBoundAt());
        }
    }

    /** Whether this server can do Microsoft 365 at all, and the directories an M365 mailbox can use. */
    public record Ms365StatusDto(boolean configured, List<DirectoryDto> directories) {
    }

    @GetMapping("/api/settings/ms365")
    public Ms365StatusDto status() {
        return new Ms365StatusDto(consent.isConfigured(),
                consent.directories().stream().map(DirectoryDto::of).toList());
    }

    /** A link to send to the customer's Microsoft 365 administrator (valid 24 h, single use). */
    @PostMapping("/api/settings/ms365/consent-link")
    public ResponseEntity<Map<String, String>> consentLink() {
        try {
            return ResponseEntity.ok(Map.of("url", consent.consentLink()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Both legs return here: the OpenID sign-in ({@code code}) → bind and go on to admin consent;
     * then the admin-consent result ({@code admin_consent} / {@code error}) → a result page.
     */
    @GetMapping("/ms365/consent/callback")
    public ResponseEntity<String> callback(@RequestParam(required = false) String code,
                                           @RequestParam(required = false) String state,
                                           @RequestParam(name = "admin_consent", required = false) String adminConsent,
                                           @RequestParam(required = false) String error,
                                           @RequestParam(name = "error_description", required = false) String errorDescription) {
        if (error != null) {
            // e.g. access_denied (the admin declined) — Microsoft's text, shown escaped and trimmed.
            String reason = errorDescription == null ? error : errorDescription.lines().findFirst().orElse(error);
            return page(HttpStatus.BAD_REQUEST, "Microsoft 365 was not connected",
                    "Microsoft reported: " + (reason.length() > 300 ? reason.substring(0, 300) + "…" : reason));
        }
        if (adminConsent != null) {
            return "True".equalsIgnoreCase(adminConsent)
                    ? page(HttpStatus.OK, "Microsoft 365 is connected",
                    "SmartFreight may now be given access to your mailbox. Your SmartFreight administrator "
                            + "will finish the setup — you can close this tab.")
                    : page(HttpStatus.BAD_REQUEST, "Microsoft 365 was not connected", "Consent was not granted.");
        }
        try {
            String directory = consent.complete(code, state);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(consent.adminConsentUrl(directory)))
                    .cacheControl(CacheControl.noStore())
                    .build();
        } catch (ConsentException e) {
            return page(HttpStatus.BAD_REQUEST, "Microsoft 365 was not connected", e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("Microsoft 365 consent callback while not configured");
            return page(HttpStatus.SERVICE_UNAVAILABLE, "Microsoft 365 was not connected",
                    "Microsoft 365 is not available on this SmartFreight server.");
        }
    }

    /** A minimal standalone page (no scripts; inline style is within the CSP). Everything is escaped. */
    private static ResponseEntity<String> page(HttpStatus status, String title, String message) {
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + HtmlUtils.htmlEscape(title) + " — SmartFreight</title></head>"
                + "<body style=\"font-family:system-ui,sans-serif;max-width:36rem;margin:4rem auto;padding:0 1rem;"
                + "line-height:1.5\"><h1 style=\"font-size:1.4rem\">" + HtmlUtils.htmlEscape(title) + "</h1>"
                + "<p>" + HtmlUtils.htmlEscape(message) + "</p></body></html>";
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .body(html);
    }
}
