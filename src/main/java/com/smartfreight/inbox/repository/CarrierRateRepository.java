package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.CarrierRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CarrierRateRepository extends JpaRepository<CarrierRate, Long> {

    /** Upsert target for CSV import — mirrors the unique index uq_carrier_rate_route. */
    Optional<CarrierRate> findByCarrierIgnoreCaseAndOriginIgnoreCaseAndDestinationIgnoreCase(
            String carrier, String origin, String destination);

    /** Stable ordering for the /rates table. */
    List<CarrierRate> findAllByOrderByCarrierAscOriginAscDestinationAsc();

    /** Rates still quotable on {@code today} (no expiry counts as valid). */
    @Query("select r from CarrierRate r where r.validUntil is null or r.validUntil >= :today")
    List<CarrierRate> findAllValidOn(@Param("today") LocalDate today);
}
