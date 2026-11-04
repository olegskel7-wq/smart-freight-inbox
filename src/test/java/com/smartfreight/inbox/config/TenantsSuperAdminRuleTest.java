package com.smartfreight.inbox.config;

import com.smartfreight.inbox.controller.TenantAdminApiController;
import org.springframework.http.HttpMethod;

import java.util.stream.Stream;

/**
 * {@code /api/admin/tenants/**} is SUPER_ADMIN, narrower than the ADMIN rule on {@code /api/admin/**} around it. ADMIN is
 * the denied role: its token also carries OPERATOR, so the OPERATOR case adds nothing.
 *
 * <p>The blank create is rejected before anything is written; the list has nothing to reject and stops at its Keycloak
 * call.
 */
class TenantsSuperAdminRuleTest extends ApiRoleRuleSupport {

    TenantsSuperAdminRuleTest() {
        super("SUPER_ADMIN", "ADMIN");
    }

    static Stream<Route> routes() {
        return Stream.of(
                tenants(HttpMethod.GET, "", KEYCLOAK_DOWN),
                tenants(HttpMethod.POST, "{}", 400));
    }

    private static Route tenants(HttpMethod method, String body, int allowedStatus) {
        return new Route(method, "/api/admin/tenants", body, allowedStatus, TenantAdminApiController.class);
    }
}
