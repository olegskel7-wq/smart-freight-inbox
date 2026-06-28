package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.RateDto;
import com.smartfreight.inbox.controller.dto.RateUploadResponse;
import com.smartfreight.inbox.controller.dto.RatesResponse;
import com.smartfreight.inbox.repository.CarrierRateRepository;
import com.smartfreight.inbox.service.RateSheetService;
import com.smartfreight.inbox.service.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * JSON API backing the React {@code /rates} page (Phase 3.2). It mirrors {@link RateSheetController}
 * (the Thymeleaf rate-sheet UI) but speaks DTOs over {@code /api/rates} and is secured by the stateless
 * resource-server chain (bearer JWT). The two controllers run side by side during the migration; this one
 * collapses to the only one when Thymeleaf is removed.
 *
 * <p>CSV import is delegated to {@link RateSheetService}; per-row errors come back in the response body so
 * the operator sees exactly which lines were rejected without losing the good rows. Every mutation returns
 * the refreshed table snapshot so the SPA re-renders without a second round trip.
 */
@RestController
@RequestMapping("/api/rates")
public class RateApiController {

    private final RateSheetService rateSheetService;
    private final CarrierRateRepository carrierRateRepository;
    private final SettingsService settingsService;

    public RateApiController(RateSheetService rateSheetService,
                            CarrierRateRepository carrierRateRepository,
                            SettingsService settingsService) {
        this.rateSheetService = rateSheetService;
        this.carrierRateRepository = carrierRateRepository;
        this.settingsService = settingsService;
    }

    /** GET /api/rates — the current tenant's rate sheet plus the feature-flag state. */
    @GetMapping
    public RatesResponse get() {
        return buildResponse();
    }

    /**
     * POST /api/rates/upload — imports a rate-sheet CSV (multipart), upserting rows by
     * (carrier, origin, destination). Malformed rows are skipped and reported; a missing/empty file or a
     * bad header answers {@code 400}. Returns the import counts/errors and the refreshed snapshot.
     */
    @PostMapping("/upload")
    public RateUploadResponse upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please choose a CSV file to upload.");
        }
        try {
            RateSheetService.ImportResult result = rateSheetService.importCsv(file.getInputStream());
            return new RateUploadResponse(
                    result.inserted(), result.updated(), result.total(), result.errors(), buildResponse());
        } catch (IllegalArgumentException e) {
            // Missing required header columns — RateSheetService throws before importing any row.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Import failed: " + e.getMessage());
        }
    }

    /**
     * DELETE /api/rates/{id} — removes one rate row. RLS scopes the lookup/delete to the current tenant.
     * Idempotent: an unknown or cross-tenant id (invisible under RLS) is a no-op, so the API answers a
     * clean snapshot instead of the 500 a bare {@code deleteById} would raise on a missing row.
     */
    @DeleteMapping("/{id}")
    public RatesResponse delete(@PathVariable Long id) {
        if (carrierRateRepository.existsById(id)) {
            carrierRateRepository.deleteById(id);
        }
        return buildResponse();
    }

    /** DELETE /api/rates — clears the whole rate sheet for the current tenant. */
    @DeleteMapping
    public RatesResponse clearAll() {
        carrierRateRepository.deleteAll();
        return buildResponse();
    }

    /** Snapshots the current tenant's rates (stable carrier/origin/destination order) into the DTO. */
    private RatesResponse buildResponse() {
        LocalDate today = LocalDate.now();
        List<RateDto> rates = carrierRateRepository.findAllByOrderByCarrierAscOriginAscDestinationAsc()
                .stream()
                .map(r -> RateDto.from(r, today))
                .toList();
        boolean featureEnabled = settingsService.isFeatureEnabled(RateSheetService.FEATURE_KEY, true);
        return new RatesResponse(featureEnabled, rates);
    }
}
