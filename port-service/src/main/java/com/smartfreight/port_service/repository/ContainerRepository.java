package com.smartfreight.port_service.repository;

import com.smartfreight.port_service.entity.Container;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for working with containers.
 *
 * {@code @Repository} – Spring automatically creates an implementation of this interface
 * using cglib bytecode generation or Java proxy objects.
 *
 * {@code JpaRepository<Entity, PrimaryKeyType>} provides CRUD methods:
 * – findAll(), findById(), save(), delete(), etc. (automatically)
 * – Custom methods are defined via {@code @Query} or the method-name convention
 */
@Repository
public interface ContainerRepository extends JpaRepository<Container, Long> {

    /**
     * Find a container by its ISO 6346 number.
     *
     * Spring Data JPA generates the SQL:
     * {@code SELECT * FROM container WHERE container_number = ?}
     * based on the method name and parameter types.
     *
     * Used by:
     * – The main inbox application via ContainerLookupService
     * – {@link com.smartfreight.port_service.scheduler.StatusUpdateScheduler} for demonstration purposes
     *
     * @param containerNumber the container number (e.g. "MSCU1234567")
     * @return Optional.of(container) if found, or Optional.empty()
     */
    Optional<Container> findByContainerNumber(String containerNumber);
}
