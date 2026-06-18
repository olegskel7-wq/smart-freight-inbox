package com.smartfreight.inbox.controller.dto;

import com.smartfreight.inbox.entity.CarrierRate;

import java.time.LocalDate;

/**
 * One carrier rate row for the React {@code /rates} table (Phase 3.2). Monetary fields are sent as
 * plain strings (from {@link java.math.BigDecimal#toPlainString()}) so the SPA renders exact prices
 * without floating-point drift, and {@code sellPrice} / {@code expired} are computed server-side —
 * the margin itself never crosses to the client, mirroring the Thymeleaf page.
 */
public record RateDto(
        Long id,
        String carrier,
        String origin,
        String destination,
        String rate,
        String currency,
        String marginPercent,
        String sellPrice,
        String validUntil,
        boolean expired
) {
    public static RateDto from(CarrierRate r, LocalDate today) {
        return new RateDto(
                r.getId(),
                r.getCarrier(),
                r.getOrigin(),
                r.getDestination(),
                r.getRate().toPlainString(),
                r.getCurrency(),
                r.getMarginPercent().toPlainString(),
                r.sellPrice().toPlainString(),
                r.getValidUntil() == null ? null : r.getValidUntil().toString(),
                !r.isValidOn(today)
        );
    }
}
