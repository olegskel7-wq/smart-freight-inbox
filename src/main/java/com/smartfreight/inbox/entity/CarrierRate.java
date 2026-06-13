package com.smartfreight.inbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One carrier rate for a route (Phase 1.2), uploaded via CSV on the /rates page.
 *
 * <p>{@code rate} is the buy price from the carrier's rate sheet; {@code marginPercent} is our
 * markup. The AI prompt only ever sees the resulting {@link #sellPrice()} — the margin itself
 * stays internal.
 *
 * <p>Unique per (carrier, origin, destination), case-insensitive — enforced by
 * {@code uq_carrier_rate_route} (migration V13); CSV re-uploads update in place.
 */
@Entity
@Table(name = "carrier_rate")
@Getter
@Setter
@NoArgsConstructor
public class CarrierRate implements Serializable {

    // The valid-rates list is JDK-serialized into the Redis cache (Phase 2.6, RateSheetCache).
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant (Phase 2.2) — assigned on insert, filters every entity query. */
    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String carrier;

    @Column(nullable = false)
    private String origin;

    @Column(nullable = false)
    private String destination;

    @Column(nullable = false)
    private BigDecimal rate;

    @Column(nullable = false)
    private String currency = "GBP";

    @Column(name = "margin_percent", nullable = false)
    private BigDecimal marginPercent = BigDecimal.ZERO;

    /** Last day the rate is quotable; {@code null} = no expiry. */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** Sell price = rate + margin, rounded to 2 dp — the price quoted to the customer. */
    public BigDecimal sellPrice() {
        BigDecimal multiplier = BigDecimal.ONE.add(
                marginPercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        return rate.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    /** Whether the rate may still be quoted on {@code today} (no expiry counts as valid). */
    public boolean isValidOn(LocalDate today) {
        return validUntil == null || !validUntil.isBefore(today);
    }
}
