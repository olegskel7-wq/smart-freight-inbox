package com.smartfreight.inbox.config;

import com.smartfreight.inbox.controller.AdminController;
import org.springframework.http.HttpMethod;

import java.util.stream.Stream;

/**
 * {@code /api/admin/**} is ADMIN. Reprocess runs in the tenant created for the test, which has no tickets, and a requeue
 * with {@code limit=0} moves nothing. Purge cannot be rejected, so it really does empty the DLQ of the test broker; no
 * test reads what is in that queue.
 */
class AdminMaintenanceRuleTest extends ApiRoleRuleSupport {

    AdminMaintenanceRuleTest() {
        super("ADMIN", "OPERATOR");
    }

    static Stream<Route> routes() {
        return Stream.of(
                admin(HttpMethod.POST, "/api/admin/reprocess"),
                admin(HttpMethod.GET, "/api/admin/dlq"),
                admin(HttpMethod.POST, "/api/admin/dlq/requeue?limit=0"),
                admin(HttpMethod.POST, "/api/admin/dlq/purge"));
    }

    private static Route admin(HttpMethod method, String path) {
        return new Route(method, path, "", 200, AdminController.class);
    }
}
