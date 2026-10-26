package com.smartfreight.inbox.service;

import com.smartfreight.inbox.config.Ms365OAuthProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * App-only access tokens for Microsoft 365 mailboxes (V38) — the client-credentials grant against
 * the CUSTOMER's directory, scoped to Exchange Online IMAP/SMTP.
 *
 * <p>A token lives ~60–90 min while an IMAP IDLE session lives for days, so callers never hold a
 * token: they ask here at every connect, and this class serves it from a per-directory cache until
 * {@link #REFRESH_MARGIN} before expiry. Each directory has its own lock, so one customer's slow or
 * failing directory never blocks another's sign-in.
 *
 * <p>Same small-WebClient shape as {@code KeycloakAdminService} (no MSAL dependency — the grant is
 * one form POST). Failures surface as {@link Ms365TokenException} carrying Entra's
 * {@code error_description} (an {@code AADSTS…} code tells the operator what the customer's admin
 * still has to do); the client secret is never part of a message.
 */
@Service
@EnableConfigurationProperties(Ms365OAuthProperties.class)
public class Ms365TokenProvider {

    /** Refresh this long before expiry — an IMAP connect must never present a token that dies mid-handshake. */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    /** A directory is a tenant GUID or a domain — anything else must not reach the token URL. */
    private static final Pattern DIRECTORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,253}");

    private final Ms365OAuthProperties props;
    private final WebClient web;
    private final Clock clock;

    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public Ms365TokenProvider(Ms365OAuthProperties props) {
        this(props, Clock.systemUTC());
    }

    Ms365TokenProvider(Ms365OAuthProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        // Boot 4 does not auto-configure a WebClient.Builder bean — static factory, as elsewhere.
        this.web = WebClient.builder().baseUrl(props.authorityUrl()).build();
    }

    /** Whether M365 sign-in is possible at all (the platform app credentials are configured). */
    public boolean isConfigured() {
        return props.isConfigured();
    }

    /**
     * A valid access token for mailboxes in {@code directoryId} — cached, or freshly requested.
     *
     * @throws Ms365TokenException when the app is not configured, the directory id is malformed,
     *                             or Entra refuses (no consent, wrong secret, unknown directory …)
     */
    public String accessToken(String directoryId) {
        if (!props.isConfigured()) {
            throw new Ms365TokenException("Microsoft 365 sign-in is not configured "
                    + "(MS365_CLIENT_ID / MS365_CLIENT_SECRET are not set)");
        }
        String directory = normalise(directoryId);
        CachedToken cached = tokens.get(directory);
        if (cached != null && cached.isFresh(clock.instant())) {
            return cached.value();
        }
        synchronized (locks.computeIfAbsent(directory, d -> new Object())) {
            cached = tokens.get(directory);
            if (cached != null && cached.isFresh(clock.instant())) {
                return cached.value();
            }
            CachedToken fresh = request(directory);
            tokens.put(directory, fresh);
            return fresh.value();
        }
    }

    /** Drops a directory's cached token — after the server rejected it (revoked consent, rotated secret). */
    public void evict(String directoryId) {
        if (directoryId != null) {
            tokens.remove(directoryId.trim().toLowerCase());
        }
    }

    private CachedToken request(String directory) {
        TokenResponse tr;
        try {
            tr = web.post()
                    .uri("/{directory}/oauth2/v2.0/token", directory)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                            .with("client_id", props.clientId())
                            .with("client_secret", props.clientSecret())
                            .with("scope", props.scope()))
                    .retrieve()
                    .bodyToMono(TokenResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw new Ms365TokenException("Microsoft 365 token request for directory " + directory
                    + " failed (HTTP " + e.getStatusCode().value() + "): " + entraReason(e), e);
        } catch (RuntimeException e) {
            throw new Ms365TokenException("Microsoft 365 token request for directory " + directory
                    + " failed: " + e.getMessage(), e);
        }
        if (tr == null || tr.access_token() == null || tr.access_token().isBlank()) {
            throw new Ms365TokenException("Microsoft 365 token response for directory " + directory
                    + " carried no access token");
        }
        Instant expiresAt = clock.instant().plusSeconds(Math.max(0, tr.expires_in()));
        return new CachedToken(tr.access_token(), expiresAt);
    }

    /** Entra's own explanation ({@code AADSTS…}), first line only — it appends trace/correlation ids. */
    private static String entraReason(WebClientResponseException e) {
        try {
            ErrorResponse err = e.getResponseBodyAs(ErrorResponse.class);
            if (err != null && err.error_description() != null) {
                return err.error_description().lines().findFirst().orElse(err.error_description());
            }
            if (err != null && err.error() != null) {
                return err.error();
            }
        } catch (RuntimeException ignored) {
            // Not Entra's JSON (proxy page, empty body) — fall through to the status text.
        }
        return e.getStatusText();
    }

    private static String normalise(String directoryId) {
        String d = directoryId == null ? "" : directoryId.trim().toLowerCase();
        if (!DIRECTORY.matcher(d).matches()) {
            throw new Ms365TokenException("Invalid Microsoft 365 directory id: expected the tenant GUID "
                    + "or its domain (e.g. contoso.onmicrosoft.com)");
        }
        return d;
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean isFresh(Instant now) {
            return now.isBefore(expiresAt.minus(REFRESH_MARGIN));
        }
    }

    private record TokenResponse(String access_token, long expires_in) {
    }

    private record ErrorResponse(String error, String error_description) {
    }

    /** A token could not be obtained — the message is safe to log and to show the operator. */
    public static class Ms365TokenException extends RuntimeException {
        public Ms365TokenException(String message) {
            super(message);
        }

        public Ms365TokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
