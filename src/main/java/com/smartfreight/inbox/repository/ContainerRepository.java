package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.Container;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// @Repository — Spring DAO для работы с контейнерами
@Repository
public interface ContainerRepository extends JpaRepository<Container, Long> {

    // Найти контейнер по уникальному номеру (например: MSCU1234567)
    // TicketProcessingService использует это для поиска контейнера после извлечения номера из письма
    Optional<Container> findByContainerNumber(String containerNumber);
}
