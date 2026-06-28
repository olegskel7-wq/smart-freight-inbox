package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.CarrierRate;
import com.smartfreight.inbox.repository.CarrierRateRepository;
import com.smartfreight.inbox.service.RateSheetService;
import com.smartfreight.inbox.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for the JSON rates API (Phase 3.2). No security/tenant filter here
 * (covered by KeycloakJwtRoleConverterTest / TenantFilterTest); this verifies the DTO snapshot
 * (server-computed sell price + expiry), the CSV upload result mapping, and the delete/clear actions.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateApiControllerTest {

    @Mock private RateSheetService rateSheetService;
    @Mock private CarrierRateRepository carrierRateRepository;
    @Mock private SettingsService settingsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CarrierRate r = new CarrierRate();
        r.setId(5L);
        r.setCarrier("Maersk");
        r.setOrigin("Felixstowe");
        r.setDestination("Rotterdam");
        r.setRate(new BigDecimal("950.00"));
        r.setCurrency("GBP");
        r.setMarginPercent(new BigDecimal("12.5"));
        r.setValidUntil(LocalDate.now().plusDays(30));
        when(carrierRateRepository.findAllByOrderByCarrierAscOriginAscDestinationAsc())
                .thenReturn(List.of(r));
        when(settingsService.isFeatureEnabled(eq(RateSheetService.FEATURE_KEY), anyBoolean()))
                .thenReturn(true);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new RateApiController(
                        rateSheetService, carrierRateRepository, settingsService))
                .build();
    }

    @Test
    void getReturnsSnapshotWithServerComputedSellPrice() throws Exception {
        mockMvc.perform(get("/api/rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureEnabled").value(true))
                .andExpect(jsonPath("$.rates[0].id").value(5))
                .andExpect(jsonPath("$.rates[0].carrier").value("Maersk"))
                .andExpect(jsonPath("$.rates[0].rate").value("950.00"))
                // 950.00 + 12.5% margin = 1068.75, computed server-side (margin itself never sent).
                .andExpect(jsonPath("$.rates[0].sellPrice").value("1068.75"))
                .andExpect(jsonPath("$.rates[0].expired").value(false));
    }

    @Test
    void featureFlagOffIsReflected() throws Exception {
        when(settingsService.isFeatureEnabled(eq(RateSheetService.FEATURE_KEY), anyBoolean()))
                .thenReturn(false);

        mockMvc.perform(get("/api/rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureEnabled").value(false));
    }

    @Test
    void uploadReturnsCountsErrorsAndSnapshot() throws Exception {
        when(rateSheetService.importCsv(any()))
                .thenReturn(new RateSheetService.ImportResult(2, 1, List.of("Line 5: rate must not be negative")));

        MockMultipartFile file = new MockMultipartFile(
                "file", "rates.csv", "text/csv", "carrier,origin,destination,rate\n".getBytes());

        mockMvc.perform(multipart("/api/rates/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.errors[0]").value("Line 5: rate must not be negative"))
                .andExpect(jsonPath("$.snapshot.rates[0].id").value(5));
    }

    @Test
    void uploadEmptyFileIsBadRequest() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);

        mockMvc.perform(multipart("/api/rates/upload").file(empty))
                .andExpect(status().isBadRequest());

        verify(rateSheetService, never()).importCsv(any());
    }

    @Test
    void uploadBadHeaderIsBadRequest() throws Exception {
        when(rateSheetService.importCsv(any()))
                .thenThrow(new IllegalArgumentException("CSV header must contain columns: carrier, origin, destination, rate"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "rates.csv", "text/csv", "wrong,header\n".getBytes());

        mockMvc.perform(multipart("/api/rates/upload").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRemovesRowAndReturnsSnapshot() throws Exception {
        when(carrierRateRepository.existsById(5L)).thenReturn(true);

        mockMvc.perform(delete("/api/rates/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[0].id").value(5));

        verify(carrierRateRepository).deleteById(5L);
    }

    @Test
    void deleteUnknownIdIsNoOpAndReturnsSnapshot() throws Exception {
        when(carrierRateRepository.existsById(999L)).thenReturn(false);

        mockMvc.perform(delete("/api/rates/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[0].id").value(5));

        verify(carrierRateRepository, never()).deleteById(any());
    }

    @Test
    void clearAllRemovesEverything() throws Exception {
        mockMvc.perform(delete("/api/rates"))
                .andExpect(status().isOk());

        verify(carrierRateRepository).deleteAll();
    }
}
