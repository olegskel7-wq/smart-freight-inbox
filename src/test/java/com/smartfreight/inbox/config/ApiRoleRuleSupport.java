package com.smartfreight.inbox.config;

import com.smartfreight.inbox.TestcontainersConfiguration;
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
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Runs a role rule of {@link SecurityConfig} through the real filter chain: every route answers 401 without a token and
 * 403 to the role just below the required one, and the required role reaches the controller. The controller tests run
 * without the chain, so they cannot see this. Each subclass declares a static {@code routes()} for one rule.
 *
 * <p>{@code jwt()} stands in for the bearer token: the decoder needs Keycloak's JWKS, and swapping that bean would
 * fork the shared context. The roles are mapped by {@link KeycloakJwtRoleConverter} and the tenant set by the real
 * {@link TenantFilter}, but the decoder (signature, issuer, {@code azp}) does not run, and neither does the chain's own
 * wiring of that converter. For the same reason MockMvc is built by hand: {@code @AutoConfigureMockMvc} would change
 * the context cache key too.
 *
 * <p>The allowed case asserts the exact answer of each controller, so a route cannot pass by failing some other way.
 * Requests must leave the shared context as they found it: prefer one the controller rejects or an unknown id, and write
 * only to the tenant created here for each test. A route that calls Keycloak first expects {@link #KEYCLOAK_DOWN}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
abstract class ApiRoleRuleSupport {

    /**
     * The answer of a route with nothing to reject before its Keycloak call. {@link TestcontainersConfiguration} points
     * the admin client at a closed port, so that call fails here and no request can reach a real Keycloak.
     */
    static final int KEYCLOAK_DOWN = 500;

    record Route(HttpMethod method, String path, String body, int allowedStatus, Class<?> controller) {

        MockHttpServletRequestBuilder toRequest() {
            return request(method, path).contentType(MediaType.APPLICATION_JSON).content(body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private final String allowedRole;
    private final String deniedRole;

    ApiRoleRuleSupport(String allowedRole, String deniedRole) {
        this.allowedRole = allowedRole;
        this.deniedRole = deniedRole;
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
                () -> tenants.save(Tenant.builder().name("role-rule-" + UUID.randomUUID()).build()).getId());
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
    void deniedRole_gets403(Route route) throws Exception {
        mvc.perform(route.toRequest().with(tokenWithRole(deniedRole))).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void allowedRole_reachesTheController(Route route) throws Exception {
        mvc.perform(route.toRequest().with(tokenWithRole(allowedRole)))
                .andExpect(status().is(route.allowedStatus()))
                .andExpect(handler().handlerType(route.controller()))
                .andExpect(result -> {
                    if (route.allowedStatus() == KEYCLOAK_DOWN) {
                        assertThat(result.getResolvedException()).isInstanceOf(WebClientRequestException.class);
                    }
                });
    }

    private RequestPostProcessor tokenWithRole(String role) {
        return jwt()
                .jwt(token -> token.claim("roles", List.of(role)).claim("tenant_id", tenant))
                .authorities(new KeycloakJwtRoleConverter());
    }
}
