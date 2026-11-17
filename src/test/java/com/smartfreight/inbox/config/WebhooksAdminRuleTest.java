package com.smartfreight.inbox.config;

import com.smartfreight.inbox.controller.WebhookApiController;
import org.springframework.http.HttpMethod;

import java.util.stream.Stream;

/**
 * {@code /api/webhooks/**} is ADMIN. No subscription is created: the empty create and an unknown id answer 400, and the
 * delivery log of an unknown id is empty, in the tenant created for the test.
 */
class WebhooksAdminRuleTest extends ApiRoleRuleSupport {

    /** No subscription has this id. */
    private static final String UNKNOWN_ID = "/api/webhooks/-1";

    WebhooksAdminRuleTest() {
        super("ADMIN", "OPERATOR");
    }

    static Stream<Route> routes() {
        return Stream.of(
                webhook(HttpMethod.GET, "/api/webhooks", "", 200),
                webhook(HttpMethod.POST, "/api/webhooks", "{}", 400),
                webhook(HttpMethod.POST, UNKNOWN_ID, "{\"enabled\":true}", 400),
                webhook(HttpMethod.DELETE, UNKNOWN_ID, "", 400),
                webhook(HttpMethod.POST, UNKNOWN_ID + "/test", "", 400),
                webhook(HttpMethod.GET, UNKNOWN_ID + "/deliveries", "", 200));
    }

    private static Route webhook(HttpMethod method, String path, String body, int allowedStatus) {
        return new Route(method, path, body, allowedStatus, WebhookApiController.class);
    }
}
