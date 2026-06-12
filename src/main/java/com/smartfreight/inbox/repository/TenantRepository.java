package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Repository for {@link Tenant}. The table carries no RLS — it is the policy catalogue. */
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    /** Lookup by unique organisation name — duplicate check on tenant onboarding (S2). */
    Optional<Tenant> findByName(String name);
}
