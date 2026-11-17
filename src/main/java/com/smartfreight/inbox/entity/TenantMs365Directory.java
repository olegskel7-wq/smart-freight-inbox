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
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * A customer's Microsoft directory (Entra tenant) bound to a SmartFreight tenant (V38).
 *
 * <p>Created only by {@code Ms365ConsentService}, from the directory id in the ID token Microsoft
 * returns after an administrator of that directory signed in through a link issued to this tenant —
 * never from user input. An {@link MailboxAuthType#MS365_OAUTH} mailbox may name only a directory
 * bound to its own tenant (composite FK), and a directory belongs to at most one tenant
 * ({@code UNIQUE(directory_id)}).
 */
@Entity
@Table(name = "tenant_ms365_directory")
@Getter
@Setter
@NoArgsConstructor
public class TenantMs365Directory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant — assigned on insert, filters every entity query. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** The directory GUID ({@code tid} claim), lower case. */
    @Column(name = "directory_id", nullable = false, updatable = false)
    private String directoryId;

    /** Who connected it: the signed-in administrator's UPN / e-mail from the ID token. */
    @Column(name = "bound_by")
    private String boundBy;

    @Column(name = "bound_at", nullable = false, updatable = false)
    private LocalDateTime boundAt = LocalDateTime.now();
}
