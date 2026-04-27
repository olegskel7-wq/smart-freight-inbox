package com.smartfreight.port_service.repository;

import com.smartfreight.port_service.entity.Container;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA saugykla darbui su konteineriais.
 *
 * {@code @Repository} – Spring automatiškai sukuria šios sąsajos implementaciją
 * naudodamas cglib baito kodo generavimą arba Java proxy objektus.
 *
 * {@code JpaRepository<Entity, PrimaryKeyType>} suteikia CRUD metodus:
 * – findAll(), findById(), save(), delete() ir kt. (automatiškai)
 * – Pasirinktiniai metodai apibrėžiami per {@code @Query} arba metodo pavadinimo konvenciją
 */
@Repository
public interface ContainerRepository extends JpaRepository<Container, Long> {

    /**
     * Rasti konteinerį pagal ISO 6346 numerį.
     *
     * Spring Data JPA generuoja SQL:
     * {@code SELECT * FROM container WHERE container_number = ?}
     * pagal metodo pavadinimą ir parametrų tipus.
     *
     * Naudojama:
     * – Pagrindiniame inbox programoje per ContainerLookupService
     * – {@link com.smartfreight.port_service.scheduler.StatusUpdateScheduler} demonstracijos tikslais
     *
     * @param containerNumber konteinerio numeris (pvz. "MSCU1234567")
     * @return Optional.of(konteineris) jei rastas, arba Optional.empty()
     */
    Optional<Container> findByContainerNumber(String containerNumber);
}
