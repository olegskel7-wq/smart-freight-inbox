package com.smartfreight.inbox.service;

import com.smartfreight.inbox.config.Ms365OAuthProperties;
import com.smartfreight.inbox.service.Ms365TokenProvider.Ms365TokenException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the real {@link Ms365TokenProvider} WebClient against a scripted Entra token endpoint
 * ({@link MockWebServer}); a mutable clock moves time across the refresh margin.
 */
class Ms365TokenProviderTest {

    private static final String DIRECTORY = "contoso.onmicrosoft.com";

    private MockWebServer server;
    private MutableClock clock;
    private Ms365TokenProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        clock = new MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
        provider = new Ms365TokenProvider(props("app-id", "app-secret"), clock);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private Ms365OAuthProperties props(String clientId, String clientSecret) {
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        return new Ms365OAuthProperties(clientId, clientSecret, baseUrl,
                "https://outlook.office365.com/.default");
    }

    private void enqueueToken(String token, long expiresIn) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"token_type\":\"Bearer\",\"access_token\":\"" + token
                        + "\",\"expires_in\":" + expiresIn + "}"));
    }

    @Test
    void requestsClientCredentialsTokenFromTheCustomersDirectory() throws Exception {
        enqueueToken("tok-1", 3599);

        assertThat(provider.accessToken(DIRECTORY)).isEqualTo("tok-1");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/contoso.onmicrosoft.com/oauth2/v2.0/token");
        String body = request.getBody().readUtf8();
        assertThat(body)
                .contains("grant_type=client_credentials")
                .contains("client_id=app-id")
                .contains("client_secret=app-secret")
                .contains("scope=https%3A%2F%2Foutlook.office365.com%2F.default");
    }

    @Test
    void servesTheCachedTokenUntilTheRefreshMargin() {
        enqueueToken("tok-1", 3600);
        enqueueToken("tok-2", 3600);

        assertThat(provider.accessToken(DIRECTORY)).isEqualTo("tok-1");
        clock.advance(Duration.ofMinutes(54));
        assertThat(provider.accessToken(DIRECTORY)).isEqualTo("tok-1");
        assertThat(server.getRequestCount()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(2));   // 56 min: inside the 5-min margin of a 60-min token
        assertThat(provider.accessToken(DIRECTORY)).isEqualTo("tok-2");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void cachesPerDirectory_caseInsensitively() {
        enqueueToken("tok-contoso", 3600);
        enqueueToken("tok-fabrikam", 3600);

        assertThat(provider.accessToken("Contoso.OnMicrosoft.com")).isEqualTo("tok-contoso");
        assertThat(provider.accessToken("fabrikam.onmicrosoft.com")).isEqualTo("tok-fabrikam");
        assertThat(provider.accessToken(DIRECTORY)).isEqualTo("tok-contoso");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void evictForcesAFreshToken() {
        enqueueToken("tok-1", 3600);
        enqueueToken("tok-2", 3600);

        provider.accessToken(DIRECTORY);
        provider.evict("CONTOSO.onmicrosoft.com");

        assertThat(provider.accessToken(DIRECTORY)).isEqualTo("tok-2");
    }

    @Test
    void entraErrorSurfacesTheAadstsReason_withoutTheSecret() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid "
                        + "client secret provided.\\r\\nTrace ID: abc\\r\\nCorrelation ID: def\"}"));

        assertThatThrownBy(() -> provider.accessToken(DIRECTORY))
                .isInstanceOf(Ms365TokenException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("AADSTS7000215: Invalid client secret provided.")
                .hasMessageNotContaining("Trace ID")
                .hasMessageNotContaining("app-secret");
    }

    @Test
    void responseWithoutAccessTokenIsAnError() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"expires_in\":3600}"));

        assertThatThrownBy(() -> provider.accessToken(DIRECTORY))
                .isInstanceOf(Ms365TokenException.class)
                .hasMessageContaining("no access token");
    }

    @Test
    void malformedDirectoryNeverReachesTheTokenUrl() {
        assertThatThrownBy(() -> provider.accessToken("../common/oauth2"))
                .isInstanceOf(Ms365TokenException.class)
                .hasMessageContaining("Invalid Microsoft 365 directory id");
        assertThatThrownBy(() -> provider.accessToken(" "))
                .isInstanceOf(Ms365TokenException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void unconfiguredAppFailsWithoutACall() {
        Ms365TokenProvider unconfigured = new Ms365TokenProvider(props("", ""), clock);

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.accessToken(DIRECTORY))
                .isInstanceOf(Ms365TokenException.class)
                .hasMessageContaining("MS365_CLIENT_ID");
        assertThat(server.getRequestCount()).isZero();
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
