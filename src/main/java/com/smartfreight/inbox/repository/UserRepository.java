package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link User} entity.
 *
 * Used by {@link com.smartfreight.inbox.config.DbUserDetailsService}
 * to look up users during authentication.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Finds a user by email address.
     * Used at login time and for checking whether a user exists.
     *
     * @param email the user's email address
     * @return the user if found
     */
    Optional<User> findByEmail(String email);

    /**
     * Users of one tenant — {@code app_user} sits outside {@code @TenantId}/RLS
     * (login bootstrap), so the management UI scopes explicitly (Phase 2.2).
     */
    List<User> findByTenantId(Long tenantId);
}
