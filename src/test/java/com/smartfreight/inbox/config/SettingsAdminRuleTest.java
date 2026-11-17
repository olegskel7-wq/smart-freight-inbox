package com.smartfreight.inbox.config;

import com.smartfreight.inbox.controller.MailboxSettingsController;
import com.smartfreight.inbox.controller.SettingsApiController;
import org.springframework.http.HttpMethod;

import java.util.stream.Stream;

/**
 * {@code /api/settings/**} is ADMIN, for every route of {@link SettingsApiController} and
 * {@link MailboxSettingsController}.
 *
 * <p>Where a route can reject the request it does, and nothing is stored; the three that cannot write only the settings
 * of the tenant created for the test. No mailbox is ever created or changed, which keeps the live reconciler out of it.
 */
class SettingsAdminRuleTest extends ApiRoleRuleSupport {

    /** No mailbox has this id. */
    private static final String UNKNOWN_ID = "/api/settings/mailboxes/-1";

    SettingsAdminRuleTest() {
        super("ADMIN", "OPERATOR");
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

    private static Route settings(HttpMethod method, String path, String body, int allowedStatus) {
        return new Route(method, path, body, allowedStatus, SettingsApiController.class);
    }

    private static Route mailbox(HttpMethod method, String path, String body, int allowedStatus) {
        return new Route(method, path, body, allowedStatus, MailboxSettingsController.class);
    }
}
