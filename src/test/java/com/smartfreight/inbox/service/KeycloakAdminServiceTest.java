package com.smartfreight.inbox.service;

import com.smartfreight.inbox.config.KeycloakAdminProperties;
import com.smartfreight.inbox.service.KeycloakAdminService.KeycloakUser;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real {@link KeycloakAdminService} WebClient against a scripted Keycloak Admin API
 * ({@link MockWebServer}). Responses are enqueued FIFO in the order the calls happen; the first
 * call of every test fetches (and then caches) the admin token.
 */
class KeycloakAdminServiceTest {

    private MockWebServer server;
    private KeycloakAdminService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        KeycloakAdminProperties props = new KeycloakAdminProperties(
                baseUrl, "smartfreight", "smartfreight-admin", "secret");
        service = new KeycloakAdminService(props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void enqueueToken() {
        server.enqueue(json("{\"access_token\":\"tok-123\",\"expires_in\":300}"));
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    @Test
    void createUser_postsRepresentation_assignsRole_andReturnsId() throws Exception {
        enqueueToken();
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Location",
                        "http://kc/admin/realms/smartfreight/users/abc-123"));
        server.enqueue(json("{\"id\":\"role-1\",\"name\":\"OPERATOR\"}"));   // GET role
        server.enqueue(new MockResponse().setResponseCode(204));            // role mapping

        String userId = service.createUser("op@acme.co", "pw-secret", 7L, "OPERATOR");

        assertThat(userId).isEqualTo("abc-123");

        RecordedRequest token = server.takeRequest();
        assertThat(token.getPath()).isEqualTo("/realms/smartfreight/protocol/openid-connect/token");
        assertThat(token.getBody().readUtf8()).contains("grant_type=client_credentials");

        RecordedRequest create = server.takeRequest();
        assertThat(create.getMethod()).isEqualTo("POST");
        assertThat(create.getPath()).isEqualTo("/admin/realms/smartfreight/users");
        assertThat(create.getHeader("Authorization")).isEqualTo("Bearer tok-123");
        String body = create.getBody().readUtf8();
        assertThat(body).contains("\"tenant_id\"").contains("\"7\"").contains("op@acme.co");

        RecordedRequest roleLookup = server.takeRequest();
        assertThat(roleLookup.getPath()).isEqualTo("/admin/realms/smartfreight/roles/OPERATOR");

        RecordedRequest roleMap = server.takeRequest();
        assertThat(roleMap.getMethod()).isEqualTo("POST");
        assertThat(roleMap.getPath())
                .isEqualTo("/admin/realms/smartfreight/users/abc-123/role-mappings/realm");
    }

    @Test
    void emailExists_queriesExactEmail() throws Exception {
        enqueueToken();
        server.enqueue(json("[{\"id\":\"u1\",\"email\":\"taken@acme.co\"}]"));

        boolean exists = service.emailExists("taken@acme.co");

        assertThat(exists).isTrue();
        server.takeRequest(); // token
        RecordedRequest q = server.takeRequest();
        assertThat(q.getPath()).contains("/admin/realms/smartfreight/users")
                .contains("email=taken@acme.co").contains("exact=true");
    }

    @Test
    void emailExists_falseOnEmptyResult() {
        enqueueToken();
        server.enqueue(json("[]"));

        assertThat(service.emailExists("free@acme.co")).isFalse();
    }

    @Test
    void listUsersByTenant_mapsUsersWithDisplayRole() throws Exception {
        enqueueToken();
        server.enqueue(json("[{\"id\":\"u1\",\"email\":\"a@acme.co\","
                + "\"attributes\":{\"tenant_id\":[\"7\"]}}]"));       // raw users
        server.enqueue(json("[{\"name\":\"OPERATOR\"}]"));            // role-mappings for u1

        List<KeycloakUser> users = service.listUsersByTenant(7L);

        assertThat(users).hasSize(1);
        KeycloakUser u = users.get(0);
        assertThat(u.id()).isEqualTo("u1");
        assertThat(u.email()).isEqualTo("a@acme.co");
        assertThat(u.role()).isEqualTo("OPERATOR");
        assertThat(u.tenantId()).isEqualTo(7L);

        server.takeRequest(); // token
        RecordedRequest listReq = server.takeRequest();
        assertThat(listReq.getPath()).contains("q=tenant_id:7");
    }

    @Test
    void listUsersByTenant_superAdminRoleWins() {
        enqueueToken();
        server.enqueue(json("[{\"id\":\"u1\",\"email\":\"boss@acme.co\","
                + "\"attributes\":{\"tenant_id\":[\"1\"]}}]"));
        server.enqueue(json("[{\"name\":\"OPERATOR\"},{\"name\":\"SUPER_ADMIN\"}]"));

        List<KeycloakUser> users = service.listUsersByTenant(1L);

        assertThat(users.get(0).role()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    void resetPassword_putsCredential() throws Exception {
        enqueueToken();
        server.enqueue(new MockResponse().setResponseCode(204));

        service.resetPassword("u1", "fresh-pw");

        server.takeRequest(); // token
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("PUT");
        assertThat(req.getPath()).isEqualTo("/admin/realms/smartfreight/users/u1/reset-password");
        assertThat(req.getBody().readUtf8()).contains("fresh-pw").contains("\"temporary\":false");
    }

    @Test
    void deleteUser_sendsDelete() throws Exception {
        enqueueToken();
        server.enqueue(new MockResponse().setResponseCode(204));

        service.deleteUser("u1");

        server.takeRequest(); // token
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("DELETE");
        assertThat(req.getPath()).isEqualTo("/admin/realms/smartfreight/users/u1");
    }

    @Test
    void findUser_returnsEmptyOnNotFound() {
        enqueueToken();
        server.enqueue(new MockResponse().setResponseCode(404));

        Optional<KeycloakUser> result = service.findUser("ghost");

        assertThat(result).isEmpty();
    }

    @Test
    void adminToken_isCachedAcrossCalls() throws Exception {
        enqueueToken();                                   // one token fetch only
        server.enqueue(json("[]"));
        server.enqueue(json("[]"));

        service.emailExists("a@acme.co");
        service.emailExists("b@acme.co");

        // First request is the single token fetch; the next two are the two queries.
        assertThat(server.takeRequest().getPath()).contains("/protocol/openid-connect/token");
        assertThat(server.takeRequest().getPath()).contains("/admin/realms/smartfreight/users");
        assertThat(server.takeRequest().getPath()).contains("/admin/realms/smartfreight/users");
    }
}
