package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.support.DefaultTenantTestExecutionListener;
import com.smartfreight.inbox.support.WithMockTenantOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real {@code documents.html} through Thymeleaf and exercises the upload round-trip
 * (Phase 2.7). Walks the full stack: Spring context, security (mock operator), controller,
 * template, multipart upload.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestExecutionListeners(
        value = DefaultTenantTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@Transactional
class DocumentPageRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockTenantOperator
    void documentsPage_renders() throws Exception {
        mockMvc.perform(get("/documents"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Document Store")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Upload document")));
    }

    @Test
    @WithMockTenantOperator
    void upload_storesDocumentAndRedirects() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "pod.pdf", "application/pdf", new byte[]{1, 2, 3, 4});

        mockMvc.perform(multipart("/documents/upload").file(file).with(csrf())
                        .param("identifier", "TEST-RENDER-1")
                        .param("docType", "POD"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/documents"));
    }
}
