package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.TenantMs365Directory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * The current tenant's bound Microsoft directories (V38). Tenant-filtered by {@code @TenantId} and
 * RLS — {@link #findByDirectoryId} therefore only ever finds the CALLER's binding, which is what the
 * mailbox validation needs ("is this directory mine?").
 */
public interface TenantMs365DirectoryRepository extends JpaRepository<TenantMs365Directory, Long> {

    List<TenantMs365Directory> findAllByOrderByIdAsc();

    Optional<TenantMs365Directory> findByDirectoryId(String directoryId);
}
