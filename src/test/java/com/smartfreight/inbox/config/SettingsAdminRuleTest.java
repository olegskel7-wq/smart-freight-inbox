package com.smartfreight.inbox.config;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.controller.MailboxSettingsController;
import com.smartfreight.inbox.controller.SettingsApiController;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import com.smartfreight.inbox.tenancy.TenantFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The {@code /api/settings/**} ADMIN rule of {@link SecurityConfig}, through the real filter chain: every route of
 * {@link SettingsApiController} and {@link MailboxSettingsController} answers 401 without a token and 403 to an
 * OPERATOR, and an ADMIN reaches the controller. The controller tests run without the chain, so they cannot see this.
 *
 * <p>{@code jwt()} stands in for the bearer token: the decoder needs Keycloak's JWKS, and swapping that bean would
 * fork the shared context. The claims still go through the real {@link KeycloakJwtRoleConverter} and
 * {@link TenantFilter}; only signature and issuer checks are skipped. For the same reason MockMvc is built by hand:
 * {@code @AutoConfigureMockMvc} would change the context cache key too.
 *
 * <p>The ADMIN case asserts the exact answer of each controller, so a route cannot pass by failing some other way.
 * Where a route can reject the request it does, and nothing is stored; the three that cannot write only the settings of
 * a tenant created for the test. No mailbox is ever created or changed, which keeps the live reconciler out of it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SettingsAdminRuleTest {

    /** No mailbox has this id. */
    private static final String UNKNOWN_ID = "/api/settings/mailboxes/-1";

    private record Route(HttpMethod method, String path, String body, int adminStatus, Class<?> controller) {

        MockHttpServletRequestBuilder toRequest() {
            return request(method, path).contentType(MediaType.APPLICATION_JSON).content(body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static Stream<Route> routes() {
        return Stream.of(
                settings(HttpMethod.GET, "/api/settings", "", 200),
                settings(HttpMethod.POST, "/api/settings/autosend", "{\"enabled\":true,\"threshold\":\"NaN\"}", 400),
                settings(HttpMethod.POST, "/api/settings/statusupdate", "{\"enabled\":true,\"milestones\":\"\"}", 400),
                settings(HttpMethod.POST, "/api/settings/quotefollowup", "{\"enabled\":true,\"delayHours\":\"soon\"}", 400),
                settings(HttpMethod.POST, "/api/settings/ollama", "{\"ollamaBaseUrl\":\"ftp://nope\"}", 400),
                settings(HttpMethod.POST, "/api/settings/routing", "{\"smartRoutingEnabled\":true}", 200),
                settings(HttpMethod.POST, "/api/settings/notifications", "{\"draftEnabled\":true}", 200),
                settings(HttpMethod.POST, "/api/settings/prompts",
                        "{\"extractionPrompt\":\"e\",\"draftingPrompt\":\"d\"}", 200),
                mailbox(HttpMethod.POST, "/api/settings/mailboxes", "{}", 400),
                mailbox(HttpMethod.PUT, UNKNOWN_ID,
                        "{\"host\":\"imap.test\",\"port\":993,\"username\":\"u\",\"password\":\"p\"}", 404),
                mailbox(HttpMethod.POST, UNKNOWN_ID + "/toggle", "{\"enabled\":true}", 404),
                mailbox(HttpMethod.DELETE, UNKNOWN_ID, "", 404));
    }

    private static Route settings(HttpMethod method, String path, String body, int adminStatus) {
        return new Route(method, path, body, adminStatus, SettingsApiController.class);
    }

    private static Route mailbox(HttpMethod method, String path, String body, int adminStatus) {
        return new Route(method, path, body, adminStatus, MailboxSettingsController.class);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TenantFilter tenantFilter;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;
    private Long tenant;

    @BeforeEach
    void setUp() {
        // Security first, then the tenant filter: Boot orders them the same way in production.
        mvc = webAppContextSetup(context).apply(springSecurity()).addFilters(tenantFilter).build();
        tenant = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("admin-rule-" + UUID.randomUUID()).build()).getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM tenant_setting WHERE tenant_id = ?", tenant);
            tenants.deleteById(tenant);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void noToken_gets401(Route route) throws Exception {
        mvc.perform(route.toRequest()).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void operator_gets403(Route route) throws Exception {
        mvc.perform(route.toRequest().with(tokenWithRole("OPERATOR"))).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void admin_reachesTheController(Route route) throws Exception {
        mvc.perform(route.toRequest().with(tokenWithRole("ADMIN")))
                .andExpect(status().is(route.adminStatus()))
                .andExpect(handler().handlerType(route.controller()));
    }

    private RequestPostProcessor tokenWithRole(String role) {
        return jwt()
                .jwt(token -> token.claim("roles", List.of(role)).claim("tenant_id", tenant))
                .authorities(new KeycloakJwtRoleConverter());
    }
}
