package com.smartfreight.inbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Keycloak Admin REST client (Phase 2.3 S2).
 *
 * <p>Bound from {@code keycloak.admin.*}. The provisioning service account
 * ({@link #clientId}/{@link #clientSecret}) authenticates with the client-credentials grant
 * against {@link #serverUrl}'s realm token endpoint and then calls the realm's Admin REST API.
 * The same {@link #realm} hosts both the login client and every tenant's operators (single-realm
 * design, decision 3).
 *
 * @param serverUrl    Keycloak base URL, no realm path (e.g. {@code http://keycloak:8081})
 * @param realm        realm that owns users + roles (e.g. {@code smartfreight})
 * @param clientId     confidential admin client id (service account enabled)
 * @param clientSecret admin client secret
 */
@ConfigurationProperties(prefix = "keycloak.admin")
public record KeycloakAdminProperties(
        String serverUrl,
        String realm,
        String clientId,
        String clientSecret) {
}
