package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.service.TenantManagementService;
import com.smartfreight.inbox.service.TenantManagementService.TenantSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for the JSON tenant-admin API (Phase 3.2). The SUPER_ADMIN gate lives in
 * the @Order(1) security chain ({@code /api/admin/tenants/**}), not here; this verifies the DTO
 * snapshot (entity flattened to four columns), that create delegates to the service and returns the
 * refreshed list, and that a validation failure maps to 400.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantAdminApiControllerTest {

    @Mock private TenantManagementService tenantManagementService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Tenant tenant = Tenant.builder()
                .id(1L)
                .name("Default")
                .createdAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();
        when(tenantManagementService.overview())
                .thenReturn(List.of(new TenantSummary(tenant, 3L)));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new TenantAdminApiController(tenantManagementService))
                .build();
    }

    @Test
    void listFlattensTenantSummaryToDto() throws Exception {
        mockMvc.perform(get("/api/admin/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Default"))
                .andExpect(jsonPath("$[0].userCount").value(3))
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    @Test
    void createDelegatesAndReturnsSnapshot() throws Exception {
        when(tenantManagementService.createTenant(eq("Acme Ltd"), eq("ops@acme.co.uk"), eq("password123")))
                .thenReturn(Tenant.builder().id(2L).name("Acme Ltd").build());

        mockMvc.perform(post("/api/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Ltd\",\"adminEmail\":\"ops@acme.co.uk\","
                                + "\"adminPassword\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Default"));

        verify(tenantManagementService).createTenant("Acme Ltd", "ops@acme.co.uk", "password123");
    }

    @Test
    void createPropagatesValidationErrorAsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("Tenant 'Default' already exists."))
                .when(tenantManagementService).createTenant(eq("Default"), eq("ops@acme.co.uk"), eq("password123"));

        mockMvc.perform(post("/api/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Default\",\"adminEmail\":\"ops@acme.co.uk\","
                                + "\"adminPassword\":\"password123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWithShortPasswordIsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("Password must be at least 8 characters long."))
                .when(tenantManagementService).createTenant(eq("Acme Ltd"), eq("ops@acme.co.uk"), eq("short"));

        mockMvc.perform(post("/api/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Ltd\",\"adminEmail\":\"ops@acme.co.uk\","
                                + "\"adminPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }
}
