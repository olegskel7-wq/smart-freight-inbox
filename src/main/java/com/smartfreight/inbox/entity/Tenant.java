package com.smartfreight.inbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One client organisation. Every tenant-owned row carries a
 * {@code tenant_id} FK to this table; Hibernate {@code @TenantId} and Postgres RLS
 * (V19) keep tenants from ever seeing each other's data (see docs/adr/0004-two-layer-multitenancy.md).
 *
 * <p>Tenant id 1 ("Default") is seeded by V18 and owns the single current mailbox
 * and all data created before multi-tenancy.
 */
@Entity
@Table(name = "tenant")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
