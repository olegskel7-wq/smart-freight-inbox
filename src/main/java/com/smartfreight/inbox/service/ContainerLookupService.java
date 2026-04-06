package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Container;
import com.smartfreight.inbox.repository.ContainerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

// Сервис поиска контейнера в базе данных по его номеру.
// Является оберткой над ContainerRepository — упрощает логирование и обработку отсутствия данных.
@Service
public class ContainerLookupService {

    private static final Logger log = LoggerFactory.getLogger(ContainerLookupService.class);

    private final ContainerRepository containerRepository;

    public ContainerLookupService(ContainerRepository containerRepository) {
        this.containerRepository = containerRepository;
    }

    // Ищет контейнер по номеру (например "MSCU1234567").
    // Возвращает Optional.empty() если контейнер не найден в системе.
    public Optional<Container> findByNumber(String containerNumber) {
        if (containerNumber == null || containerNumber.isBlank()) {
            log.warn("Container lookup called with empty number");
            return Optional.empty();
        }

        // Нормализуем: убираем пробелы и переводим в верхний регистр
        String normalized = containerNumber.trim().toUpperCase();

        Optional<Container> result = containerRepository.findByContainerNumber(normalized);

        if (result.isPresent()) {
            Container c = result.get();
            log.info("Container found: {} | status={} | location={}",
                    c.getContainerNumber(), c.getStatus(), c.getLocation());
        } else {
            log.warn("Container not found in DB: {}", normalized);
        }

        return result;
    }
}
