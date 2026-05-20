package com.smartfreight.port_service.config;

import com.smartfreight.port_service.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ApiKeyFilter}.
 *
 * Verifies that the security filter correctly enforces API key authentication:
 * – Requests without X-API-Key are rejected with 401.
 * – Requests with a wrong key are rejected with 401.
 * – Requests with the correct key are allowed through (200 or 404, never 401/403).
 *
 * Uses a full Spring context with Testcontainers Postgres so that the
 * SecurityFilterChain, ApiKeyFilter, and the real endpoint are all active.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "port.api.key=test-api-key-for-tests")
class ApiKeyFilterTest {

    private static final String TEST_API_KEY = "test-api-key-for-tests";
    private static final String TEST_CONTAINER_URL = "/api/containers/MSCU1234567";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void request_withoutApiKey_returns401() {
        ResponseEntity<String> response = restTemplate.getForEntity(TEST_CONTAINER_URL, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void request_withWrongApiKey_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ApiKeyFilter.API_KEY_HEADER, "completely-wrong-key");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                TEST_CONTAINER_URL, HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void request_withCorrectApiKey_isAuthenticated() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ApiKeyFilter.API_KEY_HEADER, TEST_API_KEY);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                TEST_CONTAINER_URL, HttpMethod.GET, entity, String.class);

        // 200 (container exists in seed data) or 404 (not seeded) — both prove auth succeeded
        assertThat(response.getStatusCode())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED)
                .isNotEqualTo(HttpStatus.FORBIDDEN);
    }
}
