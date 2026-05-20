package com.smartfreight.port_service.config;

import com.smartfreight.port_service.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link ApiKeyFilter}.
 *
 * Verifies that the security filter correctly enforces API key authentication:
 * – Requests without X-API-Key are rejected with 401.
 * – Requests with a wrong key are rejected with 401.
 * – Requests with the correct key are allowed through (200 or 404, never 401/403).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "port.api.key=test-api-key-for-tests")
class ApiKeyFilterTest {

    private static final String TEST_API_KEY = "test-api-key-for-tests";
    private static final String TEST_URL = "/api/containers/MSCU1234567";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void request_withoutApiKey_returns401() throws Exception {
        mockMvc.perform(get(TEST_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void request_withWrongApiKey_returns401() throws Exception {
        mockMvc.perform(get(TEST_URL)
                        .header(ApiKeyFilter.API_KEY_HEADER, "completely-wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void request_withCorrectApiKey_isAuthenticated() throws Exception {
        // 200 (found) or 404 (not seeded) — either proves authentication passed
        int responseStatus = mockMvc.perform(get(TEST_URL)
                        .header(ApiKeyFilter.API_KEY_HEADER, TEST_API_KEY))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(responseStatus).isNotEqualTo(401).isNotEqualTo(403);
    }
}
