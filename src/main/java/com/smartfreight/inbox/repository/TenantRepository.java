package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link Tenant}. Minimal until the tenant admin UI (Phase 2.2 S2). */
public interface TenantRepository extends JpaRepository<Tenant, Long> {
}
