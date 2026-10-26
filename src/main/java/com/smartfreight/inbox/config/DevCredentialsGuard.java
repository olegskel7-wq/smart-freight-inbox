package com.smartfreight.inbox.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gate C6: the backend refuses to start on a public deployment that still runs on DEV credentials.
 *
 * <p>{@code scripts/check-prod-env.sh} (Gate C1) already blocks such a {@code .env} before
 * {@code docker compose up} — but only when the deploy goes through it. This is the second layer,
 * inside the application: it sees the values Spring actually resolved, whatever started it.
 *
 * <p>"Public" is read from the token issuer, not from the Spring profile: the local compose stack
 * runs the {@code prod} profile too (on dev defaults, by design — a fresh clone needs no
 * {@code .env}), whereas a real deployment must serve Keycloak on its own domain (Gate C3).
 * Issuer on localhost → dev: one WARN listing what is still default. Anything else → startup fails.
 * Only property NAMES are ever logged, never values.
 */
@Component
public class DevCredentialsGuard {

    private static final Logger log = LoggerFactory.getLogger(DevCredentialsGuard.class);

    static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    /** Same floor as check-prod-env.sh. */
    static final int MIN_SECRET_LENGTH = 16;

    /**
     * Secrets the backend resolves → their dev defaults (application.yaml AND docker-compose.yml
     * fallbacks — they differ for the port-service key). Keep in sync with check-prod-env.sh.
     */
    static final Map<String, Set<String>> DEV_DEFAULTS = Map.of(
            "spring.datasource.password", Set.of("smartfreight_app"),
            "spring.flyway.password", Set.of("postgres"),
            "spring.rabbitmq.password", Set.of("smartfreight"),
            "spring.data.redis.password", Set.of("smartfreight-dev-redis"),
            "keycloak.admin.client-secret", Set.of("smartfreight-admin-secret"),
            "port.service.api-key", Set.of("local-dev-api-key", "smartfreight-port-secret"),
            "mailbox.encryption-key", Set.of());

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    public DevCredentialsGuard(Environment env) {
        List<String> weak = weakSecrets(env);
        if (weak.isEmpty()) {
            return;
        }
        String issuer = env.getProperty(ISSUER_PROPERTY, "");
        if (isLocal(issuer)) {
            log.warn("Dev credentials in use (fine on a local stack, refused on a public host): {}", weak);
            return;
        }
        throw new IllegalStateException("Refusing to start: the token issuer " + issuer
                + " is a public host, but these secrets are unset, dev defaults or shorter than "
                + MIN_SECRET_LENGTH + " characters: " + weak
                + ". Set them in .env and run scripts/check-prod-env.sh (Gate C1/C6).");
    }

    /** Property names whose value is missing, a dev default or too short — sorted, values never exposed. */
    static List<String> weakSecrets(Environment env) {
        List<String> weak = new ArrayList<>();
        DEV_DEFAULTS.forEach((name, devValues) -> {
            String v = env.getProperty(name, "");
            if (v.isBlank() || devValues.contains(v) || v.length() < MIN_SECRET_LENGTH) {
                weak.add(name);
            }
        });
        weak.sort(null);
        return weak;
    }

    static boolean isLocal(String issuer) {
        try {
            String host = URI.create(issuer.trim()).getHost();
            return host == null || LOCAL_HOSTS.contains(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            // Unparseable issuer: the JwtDecoder will fail on it anyway — don't mask that error here.
            return true;
        }
    }
}
