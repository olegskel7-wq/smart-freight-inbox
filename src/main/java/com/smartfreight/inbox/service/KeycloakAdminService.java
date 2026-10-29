package com.smartfreight.inbox.service;

import com.smartfreight.inbox.config.KeycloakAdminProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Thin Keycloak Admin REST client (Phase 2.3 S2).
 *
 * <p>A deliberately small WebClient wrapper rather than the {@code keycloak-admin-client}
 * library — same standing call the project made for resilience4j (core over a Boot-3-targeted
 * dependency). It authenticates the provisioning service account
 * ({@code keycloak.admin.client-id}) with the client-credentials grant and drives the realm's
 * Admin API: create/list/delete users, set the {@code tenant_id} attribute, assign realm roles
 * and reset passwords. The single-realm design (decision 3) means every tenant's operators live
 * in one realm and are scoped by their {@code tenant_id} user attribute.
 *
 * <p>The admin token is cached until shortly before it expires; all calls are synchronous
 * ({@code .block()}) because they run inside MVC request threads (operator UI), where a blocking
 * round-trip to Keycloak is the simplest correct model.
 */
@Service
public class KeycloakAdminService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAP =
            new ParameterizedTypeReference<>() {};

    /** Realm roles auto-assigned by Keycloak that are not application roles — ignored for display. */
    private static final String OPERATOR_ROLE = "OPERATOR";
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";

    /**
     * Tenant-level app roles this service hands out and swaps (Phase 3.3). {@link #setRealmRole}
     * strips only these before assigning a new one — SUPER_ADMIN and Keycloak's own default
     * mappings (offline_access, uma_authorization, …) are left untouched.
     */
    private static final Set<String> MANAGED_APP_ROLES = Set.of(OPERATOR_ROLE, ADMIN_ROLE);

    private final WebClient web;
    private final KeycloakAdminProperties props;

    private final Object tokenLock = new Object();
    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public KeycloakAdminService(KeycloakAdminProperties props) {
        this.props = props;
        // Boot 4 does not auto-configure a WebClient.Builder bean; build from the static factory,
        // same as OllamaClientProvider. baseUrl is the realm-less server URL.
        this.web = WebClient.builder().baseUrl(props.serverUrl()).build();
    }

    /** An operator account as seen by the management UI — Keycloak's user id is a UUID string. */
    public record KeycloakUser(String id, String email, String role, Long tenantId) {
    }

    // ---------------------------------------------------------------------
    // Admin token (client-credentials, cached)
    // ---------------------------------------------------------------------

    private String token() {
        synchronized (tokenLock) {
            if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
                return cachedToken;
            }
            TokenResponse tr = web.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", props.realm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                            .with("client_id", props.clientId())
                            .with("client_secret", props.clientSecret()))
                    .retrieve()
                    .bodyToMono(TokenResponse.class)
                    .block();
            if (tr == null || tr.access_token() == null) {
                throw new IllegalStateException("Keycloak admin token request returned no token");
            }
            cachedToken = tr.access_token();
            // Refresh a little early so an in-flight request never races the expiry.
            tokenExpiry = Instant.now().plusSeconds(Math.max(1, tr.expires_in() - 10));
            return cachedToken;
        }
    }

    private record TokenResponse(String access_token, long expires_in) {
    }

    // ---------------------------------------------------------------------
    // User provisioning
    // ---------------------------------------------------------------------

    /**
     * Creates an enabled user with the {@code tenant_id} attribute, first/last name, an initial
     * (non-temporary) password and the given realm role, then returns the new Keycloak user id (UUID).
     */
    public String createUser(String email, OperatorName name, String password, Long tenantId, String role) {
        Map<String, Object> representation = Map.of(
                "username", email,
                "email", email,
                "firstName", name.firstName(),
                "lastName", name.lastName(),
                "enabled", true,
                "emailVerified", true,
                "attributes", Map.of("tenant_id", List.of(String.valueOf(tenantId))),
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", password,
                        "temporary", false)));

        ResponseEntity<Void> response = web.post()
                .uri("/admin/realms/{realm}/users", props.realm())
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(representation)
                .retrieve()
                .toBodilessEntity()
                .block();

        String location = response == null ? null
                : response.getHeaders().getFirst(HttpHeaders.LOCATION);
        if (location == null || !location.contains("/")) {
            throw new IllegalStateException("Keycloak did not return a Location for the created user");
        }
        String userId = location.substring(location.lastIndexOf('/') + 1);
        assignRealmRole(userId, role);
        return userId;
    }

    /** Assigns one realm role to a user (looks up the role representation first). */
    public void assignRealmRole(String userId, String role) {
        Map<String, Object> roleRep = web.get()
                .uri("/admin/realms/{realm}/roles/{role}", props.realm(), role)
                .headers(h -> h.setBearerAuth(token()))
                .retrieve()
                .bodyToMono(MAP)
                .block();

        web.post()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", props.realm(), userId)
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(roleRep))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    /**
     * Swaps a user's tenant-level app role (Phase 3.3): strips any currently-mapped
     * {@link #MANAGED_APP_ROLES} (OPERATOR/ADMIN) then assigns {@code role}. SUPER_ADMIN and
     * Keycloak's own default mappings are left in place — only the OPERATOR↔ADMIN pair moves.
     */
    public void setRealmRole(String userId, String role) {
        List<Map<String, Object>> current = web.get()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", props.realm(), userId)
                .headers(h -> h.setBearerAuth(token()))
                .retrieve()
                .bodyToMono(LIST_OF_MAP)
                .block();

        List<Map<String, Object>> toRemove = current == null ? List.of()
                : current.stream()
                        .filter(r -> MANAGED_APP_ROLES.contains(r.get("name")))
                        .toList();

        if (!toRemove.isEmpty()) {
            web.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", props.realm(), userId)
                    .headers(h -> h.setBearerAuth(token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(toRemove)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        }
        assignRealmRole(userId, role);
    }

    /** Replaces a user's password (non-temporary — the operator can use it immediately). */
    public void resetPassword(String userId, String password) {
        web.put()
                .uri("/admin/realms/{realm}/users/{id}/reset-password", props.realm(), userId)
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "password", "value", password, "temporary", false))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    /** Deletes a user by id. */
    public void deleteUser(String userId) {
        web.delete()
                .uri("/admin/realms/{realm}/users/{id}", props.realm(), userId)
                .headers(h -> h.setBearerAuth(token()))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    /** True if a user already exists with this exact email (login-key uniqueness check). */
    public boolean emailExists(String email) {
        List<Map<String, Object>> users = web.get()
                .uri(b -> b.path("/admin/realms/{realm}/users")
                        .queryParam("email", email)
                        .queryParam("exact", true)
                        .build(props.realm()))
                .headers(h -> h.setBearerAuth(token()))
                .retrieve()
                .bodyToMono(LIST_OF_MAP)
                .block();
        return users != null && !users.isEmpty();
    }

    /**
     * Operators of one tenant, each resolved to its display role (one role-mapping call per
     * user — fine for the small operator teams the UI lists, no pagination).
     */
    public List<KeycloakUser> listUsersByTenant(Long tenantId) {
        return rawUsersByTenant(tenantId).stream()
                .map(u -> toKeycloakUser(u, displayRole((String) u.get("id"))))
                .toList();
    }

    /** Cheap headcount for the platform admin overview — one call, no per-user role lookups. */
    public long countUsersByTenant(Long tenantId) {
        return rawUsersByTenant(tenantId).size();
    }

    /**
     * Maps each operator's Keycloak id to their email for one tenant in a single Admin call (no
     * per-user role lookups). Used to resolve ticket assignees to a display email (Phase 3.3 S2) —
     * the ticket only stores the UUID, the UI shows who.
     */
    public Map<String, String> userEmailsByTenant(Long tenantId) {
        Map<String, String> emails = new java.util.HashMap<>();
        for (Map<String, Object> u : rawUsersByTenant(tenantId)) {
            Object id = u.get("id");
            Object email = u.get("email");
            if (id != null && email != null) {
                emails.put(id.toString(), email.toString());
            }
        }
        return emails;
    }

    /** A single user, if present — used to verify tenant ownership before mutation. */
    public Optional<KeycloakUser> findUser(String userId) {
        try {
            Map<String, Object> user = web.get()
                    .uri("/admin/realms/{realm}/users/{id}", props.realm(), userId)
                    .headers(h -> h.setBearerAuth(token()))
                    .retrieve()
                    .bodyToMono(MAP)
                    .block();
            return Optional.ofNullable(user)
                    .map(u -> toKeycloakUser(u, displayRole(userId)));
        } catch (WebClientResponseException.NotFound e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private List<Map<String, Object>> rawUsersByTenant(Long tenantId) {
        List<Map<String, Object>> users = web.get()
                .uri(b -> b.path("/admin/realms/{realm}/users")
                        .queryParam("q", "tenant_id:" + tenantId)
                        .queryParam("max", 1000)
                        .build(props.realm()))
                .headers(h -> h.setBearerAuth(token()))
                .retrieve()
                .bodyToMono(LIST_OF_MAP)
                .block();
        return users == null ? List.of() : users;
    }

    /**
     * Reduces a user's realm-role mappings to one display role by precedence
     * SUPER_ADMIN &gt; ADMIN &gt; OPERATOR (Phase 3.3 added the tenant-level ADMIN tier).
     */
    private String displayRole(String userId) {
        List<Map<String, Object>> roles = web.get()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", props.realm(), userId)
                .headers(h -> h.setBearerAuth(token()))
                .retrieve()
                .bodyToMono(LIST_OF_MAP)
                .block();
        if (roles == null) {
            return OPERATOR_ROLE;
        }
        if (roles.stream().anyMatch(r -> SUPER_ADMIN_ROLE.equals(r.get("name")))) {
            return SUPER_ADMIN_ROLE;
        }
        if (roles.stream().anyMatch(r -> ADMIN_ROLE.equals(r.get("name")))) {
            return ADMIN_ROLE;
        }
        return OPERATOR_ROLE;
    }

    @SuppressWarnings("unchecked")
    private KeycloakUser toKeycloakUser(Map<String, Object> u, String role) {
        String id = (String) u.get("id");
        String email = (String) u.get("email");
        Long tenantId = null;
        Object attributes = u.get("attributes");
        if (attributes instanceof Map<?, ?> attrs) {
            Object values = attrs.get("tenant_id");
            if (values instanceof List<?> list && !list.isEmpty() && list.get(0) != null) {
                try {
                    tenantId = Long.parseLong(list.get(0).toString().trim());
                } catch (NumberFormatException ignored) {
                    // leave null — a malformed attribute means "unknown tenant", handled by callers
                }
            }
        }
        return new KeycloakUser(id, email, role, tenantId);
    }
}
