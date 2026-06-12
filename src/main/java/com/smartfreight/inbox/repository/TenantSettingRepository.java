package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.TenantSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantSettingRepository extends JpaRepository<TenantSetting, Long> {

    /**
     * The current tenant's override for a key, if any. {@code @TenantId} scopes the query
     * to the session's tenant — callers must NOT invoke this from the system context,
     * where the filter is off and several tenants' rows would match one key.
     */
    Optional<TenantSetting> findByKey(String key);
}
