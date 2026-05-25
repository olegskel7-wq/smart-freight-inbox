package com.smartfreight.port_service.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Startup guard for the API key configuration.
 *
 * The application ships with an insecure default key ({@code dev-local-key})
 * for local development convenience. This validator makes sure that default
 * never reaches a production environment:
 *
 * – In any profile: logs a loud warning when the default key is still in use.
 * – In the {@code prod} profile: refuses to start (fail-fast) so a misconfigured
 *   deployment cannot silently run with a publicly known key.
 */
@Component
public class ApiKeyStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyStartupValidator.class);

    /** Insecure default declared in application.yaml ({@code port.api.key}). */
    static final String INSECURE_DEFAULT_KEY = "dev-local-key";

    public ApiKeyStartupValidator(@Value("${port.api.key}") String apiKey, Environment environment) {
        if (!INSECURE_DEFAULT_KEY.equals(apiKey)) {
            return;
        }

        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isProd) {
            throw new IllegalStateException(
                    "PORT_SERVICE_API_KEY must be set in the 'prod' profile — "
                            + "refusing to start with the insecure default key.");
        }

        log.warn("⚠️  Using the insecure default API key '{}'. "
                + "Set PORT_SERVICE_API_KEY for any non-development environment.", INSECURE_DEFAULT_KEY);
    }
}
