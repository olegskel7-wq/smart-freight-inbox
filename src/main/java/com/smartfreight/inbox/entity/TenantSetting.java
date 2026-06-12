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

/**
 * Per-tenant override of an {@link AppSetting} key (Phase 2.2 S2).
 *
 * <p>Resolution order lives in {@link com.smartfreight.inbox.service.SettingsService}:
 * tenant_setting (current tenant) → app_setting (platform default) → code default.
 * A row here only exists once a tenant saves something on {@code /settings} — absence
 * means "inherit the platform default", which is why no data migration seeds this table.
 *
 * <p>Unique per (tenant, key) — V20. Guarded by both isolation layers: {@code @TenantId}
 * for entity queries and the V20 RLS policy for everything else.
 */
@Entity
@Table(name = "tenant_setting")
@Getter
@Setter
@NoArgsConstructor
public class TenantSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant — assigned on insert, filters every entity query. */
    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "key", nullable = false)
    private String key;

    @Column(name = "value", nullable = false)
    private String value;

    public TenantSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
