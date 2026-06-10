package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.CarrierRate;
import com.smartfreight.inbox.repository.CarrierRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateSheetService}: CSV import (happy path, optional columns, bad rows,
 * upsert) and rate-context building (route matching, expiry, feature flag, word boundaries).
 */
@ExtendWith(MockitoExtension.class)
class RateSheetServiceTest {

    @Mock private CarrierRateRepository repository;
    @Mock private SettingsService settingsService;

    private RateSheetService service;

    @BeforeEach
    void setUp() {
        service = new RateSheetService(repository, settingsService);
        lenient().when(settingsService.isFeatureEnabled(anyString(), anyBoolean())).thenReturn(true);
        lenient().when(repository.save(any(CarrierRate.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.findByCarrierIgnoreCaseAndOriginIgnoreCaseAndDestinationIgnoreCase(
                anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private CarrierRate rate(String carrier, String origin, String destination,
                             String buyRate, String margin, LocalDate validUntil) {
        CarrierRate r = new CarrierRate();
        r.setCarrier(carrier);
        r.setOrigin(origin);
        r.setDestination(destination);
        r.setRate(new BigDecimal(buyRate));
        r.setMarginPercent(new BigDecimal(margin));
        r.setValidUntil(validUntil);
        return r;
    }

    // ---------- importCsv ----------

    @Test
    void importCsv_validRows_insertsAll() throws Exception {
        String content = """
                carrier,origin,destination,rate,currency,margin_percent,valid_until
                Maersk,Felixstowe,Rotterdam,950.00,GBP,12.5,2026-12-31
                DFDS,Immingham,Gothenburg,1180.00,GBP,10,
                """;

        RateSheetService.ImportResult result = service.importCsv(csv(content));

        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.updated()).isZero();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void importCsv_optionalColumnsOmitted_appliesDefaults() throws Exception {
        String content = """
                carrier,origin,destination,rate
                Maersk,Felixstowe,Rotterdam,950.00
                """;

        RateSheetService.ImportResult result = service.importCsv(csv(content));

        assertThat(result.inserted()).isEqualTo(1);
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(r ->
                r.getCurrency().equals("GBP")
                        && r.getMarginPercent().compareTo(BigDecimal.ZERO) == 0
                        && r.getValidUntil() == null));
    }

    @Test
    void importCsv_badRow_skippedAndReported_goodRowStillImported() throws Exception {
        String content = """
                carrier,origin,destination,rate,currency,margin_percent,valid_until
                Maersk,Felixstowe,Rotterdam,not-a-number,GBP,12.5,2026-12-31
                DFDS,Immingham,Gothenburg,1180.00,GBP,10,
                """;

        RateSheetService.ImportResult result = service.importCsv(csv(content));

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).startsWith("Line 2");
    }

    @Test
    void importCsv_existingRoute_updatesInsteadOfInserting() throws Exception {
        CarrierRate existing = rate("Maersk", "Felixstowe", "Rotterdam", "900.00", "10", null);
        when(repository.findByCarrierIgnoreCaseAndOriginIgnoreCaseAndDestinationIgnoreCase(
                eq("Maersk"), eq("Felixstowe"), eq("Rotterdam"))).thenReturn(Optional.of(existing));

        String content = """
                carrier,origin,destination,rate
                Maersk,Felixstowe,Rotterdam,950.00
                """;

        RateSheetService.ImportResult result = service.importCsv(csv(content));

        assertThat(result.inserted()).isZero();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(existing.getRate()).isEqualByComparingTo("950.00");
    }

    @Test
    void importCsv_missingRequiredHeader_throws() {
        String content = """
                carrier,origin,price
                Maersk,Felixstowe,950.00
                """;

        assertThatThrownBy(() -> service.importCsv(csv(content)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CSV header");
    }

    // ---------- buildRateContext ----------

    @Test
    void buildRateContext_routeMentioned_returnsSellPriceLine() {
        when(repository.findAllValidOn(any(LocalDate.class))).thenReturn(List.of(
                rate("Maersk", "Felixstowe", "Rotterdam", "950.00", "12.5", null)));

        String context = service.buildRateContext(
                "Hi, what would shipping from Felixstowe to Rotterdam cost next week?");

        // 950 * 1.125 = 1068.75 — the prompt sees the sell price, never the buy rate or margin
        assertThat(context).contains("Felixstowe").contains("Rotterdam").contains("1068.75 GBP");
        assertThat(context).doesNotContain("950").doesNotContain("12.5");
    }

    @Test
    void buildRateContext_routeNotMentioned_returnsEmpty() {
        when(repository.findAllValidOn(any(LocalDate.class))).thenReturn(List.of(
                rate("Maersk", "Felixstowe", "Rotterdam", "950.00", "12.5", null)));

        String context = service.buildRateContext("Any update on container MSCU1234567?");

        assertThat(context).isEmpty();
    }

    @Test
    void buildRateContext_onlyOriginMentioned_returnsEmpty() {
        when(repository.findAllValidOn(any(LocalDate.class))).thenReturn(List.of(
                rate("Maersk", "Felixstowe", "Rotterdam", "950.00", "12.5", null)));

        String context = service.buildRateContext("How busy is Felixstowe today?");

        assertThat(context).isEmpty();
    }

    @Test
    void buildRateContext_wordBoundaryRespected_noSubstringMatch() {
        when(repository.findAllValidOn(any(LocalDate.class))).thenReturn(List.of(
                rate("P&O", "Hull", "Zeebrugge", "780.00", "10", null)));

        // "fully" contains "hull" backwards-ish but no word match for either location
        String context = service.buildRateContext(
                "We are fully booked via Zeebrugge alternatives this month.");

        assertThat(context).isEmpty();
    }

    @Test
    void buildRateContext_featureDisabled_returnsEmptyWithoutDbCall() {
        when(settingsService.isFeatureEnabled(eq(RateSheetService.FEATURE_KEY), anyBoolean()))
                .thenReturn(false);

        String context = service.buildRateContext("Felixstowe to Rotterdam please");

        assertThat(context).isEmpty();
        verify(repository, never()).findAllValidOn(any());
    }

    @Test
    void buildRateContext_validUntilFormattedForPrompt() {
        when(repository.findAllValidOn(any(LocalDate.class))).thenReturn(List.of(
                rate("Maersk", "Felixstowe", "Rotterdam", "1000.00", "0", LocalDate.of(2026, 12, 31))));

        String context = service.buildRateContext("Quote please: Felixstowe to Rotterdam");

        assertThat(context).contains("valid until 31 Dec 2026");
    }
}
