package com.smartfreight.inbox.config;

import com.smartfreight.inbox.controller.UserApiController;
import org.springframework.http.HttpMethod;

import java.util.stream.Stream;

/**
 * {@code /api/users/**} is ADMIN. Three routes are rejected before the Keycloak call; the list and the delete have
 * nothing to reject and stop at that call.
 */
class UsersAdminRuleTest extends ApiRoleRuleSupport {

    private static final String USER = "/api/users/not-a-user";

    UsersAdminRuleTest() {
        super("ADMIN", "OPERATOR");
    }

    static Stream<Route> routes() {
        return Stream.of(
                users(HttpMethod.GET, "/api/users", "", KEYCLOAK_DOWN),
                users(HttpMethod.POST, "/api/users", "{}", 400),
                users(HttpMethod.POST, USER + "/role", "{\"role\":\"SUPER_ADMIN\"}", 400),
                users(HttpMethod.POST, USER + "/password", "{\"newPassword\":\"short\"}", 400),
                users(HttpMethod.DELETE, USER, "", KEYCLOAK_DOWN));
    }

    private static Route users(HttpMethod method, String path, String body, int allowedStatus) {
        return new Route(method, path, body, allowedStatus, UserApiController.class);
    }
}
