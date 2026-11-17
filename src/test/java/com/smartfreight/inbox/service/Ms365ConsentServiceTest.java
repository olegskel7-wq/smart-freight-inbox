package com.smartfreight.inbox.service;

import com.smartfreight.inbox.config.Ms365OAuthProperties;
import com.smartfreight.inbox.entity.TenantMs365Directory;
import com.smartfreight.inbox.repository.TenantMs365DirectoryRepository;
import com.smartfreight.inbox.service.Ms365ConsentService.ConsentException;
import com.smartfreight.inbox.tenancy.TenantContext;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Microsoft 365 consent flow (V38) against a fake Microsoft token endpoint: the directory a
 * tenant gets bound to is the {@code tid} of the ID token Microsoft returned for an ADMINISTRATOR who
 * signed in through a link that tenant issued — never anything the browser could choose.
 */
class Ms365ConsentServiceTest {

    private static final String CLIENT_ID = "11111111-2222-3333-4444-555555555555";
    private static final String DIRECTORY = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String GLOBAL_ADMIN = "62e90394-69f5-4237-9190-012177145e10";
    private static final String REDIRECT = "https://app.example.co.uk/ms365/consent/callback";

    private MockWebServer server;
    private Ms365ConsentStates states;
    private TenantMs365DirectoryRepository directories;
    private Ms365ConsentService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String base = server.url("/").toString().replaceAll("/$", "");
        states = mock(Ms365ConsentStates.class);
        directories = mock(TenantMs365DirectoryRepository.class);
        service = new Ms365ConsentService(
                new Ms365OAuthProperties(CLIENT_ID, "secret", base, "https://outlook.office365.com/.default", REDIRECT),
                states, directories);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static String idToken(String claimsJson) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    private static String claims(String tid, String wids) {
        return "{\"aud\":\"" + CLIENT_ID + "\",\"iss\":\"https://login.microsoftonline.com/" + tid + "/v2.0\","
                + "\"tid\":\"" + tid + "\",\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + ","
                + "\"preferred_username\":\"it-admin@contoso.co.uk\",\"wids\":" + wids + "}";
    }

    private void microsoftReturns(String claimsJson) {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"id_token\":\"" + idToken(claimsJson) + "\",\"access_token\":\"x\"}"));
    }

    @Test
    void consentLink_remembersTheTenant_andAsksMicrosoftForAnOpenIdSignIn() {
        when(states.issue(7L)).thenReturn("state-7");

        String url = TenantContext.callAs(7L, service::consentLink);

        var q = UriComponentsBuilder.fromUriString(url).build().getQueryParams();
        assertThat(url).contains("/organizations/oauth2/v2.0/authorize");
        assertThat(q.getFirst("client_id")).isEqualTo(CLIENT_ID);
        assertThat(q.getFirst("response_type")).isEqualTo("code");
        assertThat(q.getFirst("state")).isEqualTo("state-7");
        assertThat(q.getFirst("redirect_uri")).isEqualTo(REDIRECT);
    }

    @Test
    void complete_bindsTheDirectoryFromTheIdToken_toTheTenantThatIssuedTheLink() throws Exception {
        when(states.consume("state-7")).thenReturn(Optional.of(7L));
        when(directories.findByDirectoryId(DIRECTORY)).thenReturn(Optional.empty());
        AtomicReference<Long> savedUnder = new AtomicReference<>();
        AtomicReference<TenantMs365Directory> saved = new AtomicReference<>();
        when(directories.saveAndFlush(any())).thenAnswer(inv -> {
            savedUnder.set(TenantContext.tenantIdOrNull());
            saved.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        microsoftReturns(claims(DIRECTORY.toUpperCase(), "[\"" + GLOBAL_ADMIN + "\"]"));

        String bound = service.complete("the-code", "state-7");

        assertThat(bound).isEqualTo(DIRECTORY);
        assertThat(savedUnder.get()).isEqualTo(7L);
        assertThat(saved.get().getDirectoryId()).isEqualTo(DIRECTORY);
        assertThat(saved.get().getBoundBy()).isEqualTo("it-admin@contoso.co.uk");
        RecordedRequest redeem = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(redeem.getPath()).isEqualTo("/organizations/oauth2/v2.0/token");
        String form = redeem.getBody().readUtf8();
        assertThat(form).contains("grant_type=authorization_code", "code=the-code", "client_secret=secret");
    }

    @Test
    void complete_nonAdministrator_isRefused_andNothingIsBound() {
        // A phished ordinary employee of the customer must not be able to bind their company.
        when(states.consume("s")).thenReturn(Optional.of(7L));
        microsoftReturns(claims(DIRECTORY, "[\"b79fbf4d-3ef9-4689-8143-76b194e85509\"]")); // "member" role

        assertThatThrownBy(() -> service.complete("code", "s"))
                .isInstanceOf(ConsentException.class).hasMessageContaining("administrator");
        verify(directories, never()).saveAndFlush(any());
    }

    @Test
    void complete_noRolesClaim_isRefused() {
        when(states.consume("s")).thenReturn(Optional.of(7L));
        microsoftReturns("{\"aud\":\"" + CLIENT_ID + "\",\"iss\":\"https://login.microsoftonline.com/" + DIRECTORY
                + "/v2.0\",\"tid\":\"" + DIRECTORY + "\",\"exp\":" + Instant.now().plusSeconds(60).getEpochSecond() + "}");

        assertThatThrownBy(() -> service.complete("code", "s")).isInstanceOf(ConsentException.class);
        verify(directories, never()).saveAndFlush(any());
    }

    @Test
    void complete_tokenForAnotherApp_isRefused() {
        when(states.consume("s")).thenReturn(Optional.of(7L));
        microsoftReturns(claims(DIRECTORY, "[\"" + GLOBAL_ADMIN + "\"]").replace(CLIENT_ID, "99999999-0000-0000-0000-000000000000"));

        assertThatThrownBy(() -> service.complete("code", "s")).isInstanceOf(ConsentException.class);
        verify(directories, never()).saveAndFlush(any());
    }

    @Test
    void complete_unknownOrUsedState_neverReachesMicrosoft() {
        when(states.consume("forged")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete("code", "forged"))
                .isInstanceOf(ConsentException.class).hasMessageContaining("expired");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void complete_directoryOfAnotherTenant_isRefused() {
        when(states.consume("s")).thenReturn(Optional.of(7L));
        when(directories.findByDirectoryId(DIRECTORY)).thenReturn(Optional.empty()); // RLS hides the other tenant's row
        when(directories.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_ms365_directory"));
        microsoftReturns(claims(DIRECTORY, "[\"" + GLOBAL_ADMIN + "\"]"));

        assertThatThrownBy(() -> service.complete("code", "s"))
                .isInstanceOf(ConsentException.class).hasMessageContaining("another");
    }

    @Test
    void complete_alreadyBoundToThisTenant_isIdempotent() {
        when(states.consume("s")).thenReturn(Optional.of(7L));
        when(directories.findByDirectoryId(DIRECTORY)).thenReturn(Optional.of(new TenantMs365Directory()));
        microsoftReturns(claims(DIRECTORY, "[\"" + GLOBAL_ADMIN + "\"]"));

        assertThat(service.complete("code", "s")).isEqualTo(DIRECTORY);
        verify(directories, never()).saveAndFlush(any());
    }

    @Test
    void complete_microsoftRefusesTheCode_isAConsentError() {
        when(states.consume("s")).thenReturn(Optional.of(7L));
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> service.complete("code", "s")).isInstanceOf(ConsentException.class);
    }

    @Test
    void adminConsentUrl_targetsTheBoundDirectory() {
        String url = service.adminConsentUrl(DIRECTORY);

        assertThat(url).contains("/" + DIRECTORY + "/v2.0/adminconsent");
        assertThat(UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("client_id")).isEqualTo(CLIENT_ID);
    }

    @Test
    void notConfigured_noLink() {
        Ms365ConsentService off = new Ms365ConsentService(
                new Ms365OAuthProperties("", "", "https://login.microsoftonline.com", "s", REDIRECT), states, directories);

        assertThatThrownBy(() -> TenantContext.callAs(7L, off::consentLink)).isInstanceOf(IllegalStateException.class);
    }
}
