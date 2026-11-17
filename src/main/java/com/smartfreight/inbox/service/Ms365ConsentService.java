package com.smartfreight.inbox.service;

import com.smartfreight.inbox.config.Ms365OAuthProperties;
import com.smartfreight.inbox.entity.TenantMs365Directory;
import com.smartfreight.inbox.repository.TenantMs365DirectoryRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Connects a customer's Microsoft directory to a SmartFreight tenant (V38) — the only way a
 * {@link TenantMs365Directory} row comes into existence.
 *
 * <p>Why a flow and not a form field: our ONE multi-tenant Entra app gets a token for any directory
 * that consented to it, and a directory id is public. Typed in, it would let tenant A point a mailbox
 * at customer B's directory and read B's mail. So the directory id must come from Microsoft itself,
 * for a person who administers that directory:
 * <ol>
 *   <li>a tenant ADMIN asks for a link ({@link #consentLink}); its {@code state} remembers the tenant
 *       ({@link Ms365ConsentStates}, single use, 24 h);</li>
 *   <li>the customer's IT administrator opens it and signs in to Microsoft (OpenID Connect,
 *       authorization code);</li>
 *   <li>{@link #complete} redeems the code at Microsoft's token endpoint with our client secret and
 *       reads the directory ({@code tid}) and the user's directory roles ({@code wids}) from the
 *       returned ID token. That token came straight from Microsoft over TLS in exchange for our own
 *       secret, which OpenID Connect Core 3.1.3.7 accepts in place of a signature check — nothing the
 *       browser sent decides the directory;</li>
 *   <li>only an administrator role is accepted (a phished ordinary employee cannot bind their
 *       company), the directory is bound to the tenant (UNIQUE across tenants), and the browser goes
 *       on to Microsoft's admin-consent page for that directory ({@link #adminConsentUrl}).</li>
 * </ol>
 * The {@code wids} claim needs {@code "groupMembershipClaims": "DirectoryRole"} (or {@code "All"}) in
 * our app's manifest — docs/ops/m365-oauth2-setup.md.
 */
@Service
public class Ms365ConsentService {

    private static final Logger log = LoggerFactory.getLogger(Ms365ConsentService.class);

    /**
     * Directory role template ids that may grant an application admin consent: Global Administrator,
     * Privileged Role Administrator, Cloud Application Administrator, Application Administrator.
     */
    static final Set<String> ADMIN_ROLES = Set.of(
            "62e90394-69f5-4237-9190-012177145e10",
            "e8611ab8-c189-46e8-94e1-60213ab1f814",
            "158c047a-c907-4556-b7ef-446551a6b5f7",
            "9b895d92-2cd3-44c7-9d02-a6ac2d5ea5c3");

    private static final Pattern GUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Ms365OAuthProperties props;
    private final Ms365ConsentStates states;
    private final TenantMs365DirectoryRepository directories;
    private final WebClient web;

    public Ms365ConsentService(Ms365OAuthProperties props, Ms365ConsentStates states,
                               TenantMs365DirectoryRepository directories) {
        this.props = props;
        this.states = states;
        this.directories = directories;
        this.web = WebClient.builder().baseUrl(props.authorityUrl()).build();
    }

    /** The current tenant's connected directories, oldest first. */
    public List<TenantMs365Directory> directories() {
        return directories.findAllByOrderByIdAsc();
    }

    /**
     * Step 1: a sign-in link for the customer's administrator, remembering the current tenant.
     *
     * @throws IllegalStateException when the platform's Entra app is not configured
     */
    public String consentLink() {
        requireConfigured();
        String state = states.issue(TenantContext.requireTenantId());
        return UriComponentsBuilder.fromUriString(props.authorityUrl())
                .path("/organizations/oauth2/v2.0/authorize")
                .queryParam("client_id", props.clientId())
                .queryParam("response_type", "code")
                .queryParam("response_mode", "query")
                .queryParam("redirect_uri", props.consentRedirectUri())
                .queryParam("scope", "openid profile email")
                .queryParam("prompt", "select_account")
                .queryParam("state", state)
                .encode()
                .toUriString();
    }

    /**
     * Steps 3–4: redeem the sign-in, check the role, bind the directory to the tenant that issued
     * the link. Idempotent for a directory already bound to that same tenant.
     *
     * @return the bound directory id (lower-case GUID)
     * @throws ConsentException with a message safe to show the customer's administrator
     */
    public String complete(String code, String state) {
        requireConfigured();
        Long tenantId = states.consume(state).orElseThrow(() -> new ConsentException(
                "This link has expired or was already used. Ask your SmartFreight administrator for a new one."));
        if (code == null || code.isBlank()) {
            throw new ConsentException("Microsoft did not return a sign-in code.");
        }
        Map<String, Object> claims = idTokenClaims(redeem(code));

        String directory = String.valueOf(claims.get("tid")).toLowerCase(Locale.ROOT);
        if (!GUID.matcher(directory).matches()
                || !props.clientId().equals(claims.get("aud"))
                || !("https://login.microsoftonline.com/" + directory + "/v2.0").equalsIgnoreCase(String.valueOf(claims.get("iss")))
                || !notExpired(claims.get("exp"))) {
            log.warn("Microsoft 365 consent for tenant {}: ID token failed validation (tid/aud/iss/exp)", tenantId);
            throw new ConsentException("Microsoft returned an unexpected sign-in. Please try again.");
        }
        if (!isAdministrator(claims.get("wids"))) {
            log.warn("Microsoft 365 consent for tenant {}: directory {} signed in without an admin role", tenantId, directory);
            throw new ConsentException("Sign in with an administrator account of your Microsoft 365 organisation "
                    + "(Global Administrator, Privileged Role Administrator, Cloud Application Administrator or "
                    + "Application Administrator).");
        }
        String boundBy = firstString(claims, "preferred_username", "email", "upn");
        bind(tenantId, directory, boundBy);
        return directory;
    }

    /** Step 4: Microsoft's admin-consent page for the bound directory; returns to the same callback. */
    public String adminConsentUrl(String directory) {
        return UriComponentsBuilder.fromUriString(props.authorityUrl())
                .pathSegment(directory, "v2.0", "adminconsent")
                .queryParam("client_id", props.clientId())
                .queryParam("scope", props.scope())
                .queryParam("redirect_uri", props.consentRedirectUri())
                // Leg 2 changes nothing on our side — the marker only tells the callback which leg it is.
                .queryParam("state", "admin-consent")
                .encode()
                .toUriString();
    }

    private void bind(Long tenantId, String directory, String boundBy) {
        TenantContext.runAs(tenantId, () -> {
            if (directories.findByDirectoryId(directory).isPresent()) {
                return; // already ours — re-running the flow (e.g. to re-consent) is fine
            }
            TenantMs365Directory row = new TenantMs365Directory();
            row.setDirectoryId(directory);
            row.setBoundBy(boundBy);
            try {
                directories.saveAndFlush(row);
            } catch (DataIntegrityViolationException e) {
                // UNIQUE(directory_id): bound to ANOTHER tenant (RLS hid it from the lookup above).
                log.warn("Microsoft 365 consent for tenant {}: directory {} is bound to another tenant", tenantId, directory);
                throw new ConsentException("This Microsoft 365 organisation is already connected to another "
                        + "SmartFreight account. Contact SmartFreight support.");
            }
        });
        log.info("Microsoft 365 directory {} connected to tenant {}", directory, tenantId);
    }

    private String redeem(String code) {
        TokenResponse response;
        try {
            response = web.post()
                    .uri("/organizations/oauth2/v2.0/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                            .with("client_id", props.clientId())
                            .with("client_secret", props.clientSecret())
                            .with("code", code)
                            .with("redirect_uri", props.consentRedirectUri())
                            .with("scope", "openid profile email"))
                    .retrieve()
                    .bodyToMono(TokenResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("Microsoft 365 consent: code redemption failed (HTTP {})", e.getStatusCode().value());
            throw new ConsentException("Microsoft refused the sign-in. Please try again.");
        } catch (RuntimeException e) {
            log.warn("Microsoft 365 consent: code redemption failed: {}", e.getMessage());
            throw new ConsentException("Microsoft could not be reached. Please try again later.");
        }
        if (response == null || response.id_token() == null || response.id_token().isBlank()) {
            throw new ConsentException("Microsoft returned no sign-in details. Please try again.");
        }
        return response.id_token();
    }

    static Map<String, Object> idTokenClaims(String idToken) {
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            throw new ConsentException("Microsoft returned an unexpected sign-in. Please try again.");
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return JsonParserFactory.getJsonParser().parseMap(json);
        } catch (IllegalArgumentException e) { // bad base64, or JsonParseException (a subclass)
            throw new ConsentException("Microsoft returned an unexpected sign-in. Please try again.");
        }
    }

    static boolean isAdministrator(Object wids) {
        return wids instanceof Collection<?> roles
                && roles.stream().anyMatch(r -> ADMIN_ROLES.contains(String.valueOf(r).toLowerCase(Locale.ROOT)));
    }

    private static boolean notExpired(Object exp) {
        return exp instanceof Number n && Instant.ofEpochSecond(n.longValue()).isAfter(Instant.now());
    }

    private static String firstString(Map<String, Object> claims, String... names) {
        for (String name : names) {
            if (claims.get(name) instanceof String s && !s.isBlank()) {
                return s.length() > 320 ? s.substring(0, 320) : s;
            }
        }
        return null;
    }

    private void requireConfigured() {
        if (!props.isConfigured()) {
            throw new IllegalStateException("Microsoft 365 is not configured on this server "
                    + "(MS365_CLIENT_ID / MS365_CLIENT_SECRET).");
        }
    }

    private record TokenResponse(String id_token) {
    }

    /** A consent step failed; the message is meant for the customer's administrator. */
    public static class ConsentException extends RuntimeException {
        public ConsentException(String message) {
            super(message);
        }
    }
}
