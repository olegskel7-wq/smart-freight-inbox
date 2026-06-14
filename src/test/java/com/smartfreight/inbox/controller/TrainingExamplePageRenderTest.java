package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.support.DefaultTenantTestExecutionListener;
import com.smartfreight.inbox.support.WithMockTenantOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real {@code training-examples.html} through Thymeleaf (Phase 3.1) — full stack:
 * Spring context, security (mock operator), controller, template.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestExecutionListeners(
        value = DefaultTenantTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@Transactional
class TrainingExamplePageRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockTenantOperator
    void trainingExamplesPage_renders() throws Exception {
        mockMvc.perform(get("/training-examples"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AI Training Examples")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("How the feedback loop works")));
    }
}
