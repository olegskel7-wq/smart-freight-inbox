package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin API контроллер для операций обслуживания (доступен только localhost).
 * 
 * Содержит "опасные" операции для разработчиков и администраторов:
 * - Переобработка неудачных/застрявших тикетов
 * - Очистка данных
 * 
 * Защита: все эндпоинты проверяют что запрос идёт с localhost (127.0.0.1 или ::1).
 * Предположение: в production этот контроллер будет недоступен снаружи (firewall/reverse proxy).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final EmailTicketRepository emailTicketRepository;
    private final TicketProcessingService ticketProcessingService;

    public AdminController(EmailTicketRepository emailTicketRepository,
                           TicketProcessingService ticketProcessingService) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketProcessingService = ticketProcessingService;
    }

    /**
     * POST /admin/reprocess — переобработать все застрявшие/неудачные тикеты.
     * 
     * Используется когда:
     * 1. Ollama был недоступен и много тикетов перешли в FAILED
     * 2. После исправления Ollama нужно переобработать их
     * 3. Оператор случайно отклонил черновик (RETRY_LATER) и нужно снова запустить AI
     * 
     * Процесс:
     * 1. Проверяем что запрос идёт с localhost (localhost-only)
     * 2. Ищем все тикеты со статусом NEW, FAILED или RETRY_LATER
     * 3. FAILED тикеты сбрасываем в NEW (и resetCount = 0)
     * 4. Синхронно запускаем процесс для каждого тикета в текущем потоке
     * 5. Возвращаем количество переобработанных тикетов
     * 
     * Внимание: операция синхронная, при наличии 1000+ тикетов может занять время.
     * Каждый тикет вызывает Ollama (150сек таймаут) — используйте если уверены в AI доступности.
     * 
     * @param request HTTP запрос (для проверки remoteAddr)
     * @return 200 OK с количеством переобработанных тикетов или 403 если не localhost
     */
    @PostMapping("/reprocess")
    @Transactional
    public ResponseEntity<String> reprocessAll(HttpServletRequest request) {
        // Безопасность: разрешаем только localhost
        if (!isLocalhost(request)) {
            log.warn("Rejected /admin/reprocess from remote address: {}", request.getRemoteAddr());
            return ResponseEntity.status(403).body("Forbidden: admin endpoints are local-only.");
        }

        // Ищем все тикеты со статусами: NEW, FAILED, RETRY_LATER
        List<EmailTicket> pending = emailTicketRepository.findAll().stream()
                .filter(t -> t.getStatus() == TicketStatus.NEW
                          || t.getStatus() == TicketStatus.FAILED
                          || t.getStatus() == TicketStatus.RETRY_LATER)
                .toList();

        if (pending.isEmpty()) {
            return ResponseEntity.ok("No tickets to reprocess.");
        }

        // Подготовка: FAILED тикеты сбрасываем в NEW чтобы TicketProcessingService их принял
        pending.forEach(t -> {
            if (t.getStatus() == TicketStatus.FAILED) {
                // Сбрасываем счётчик попыток — это новая попытка
                t.setStatus(TicketStatus.NEW);
                t.setRetryCount(0);
                emailTicketRepository.save(t);
            }
        });

        // Запускаем обработку для каждого тикета (в текущем потоке, синхронно)
        List<Long> ids = pending.stream().map(EmailTicket::getId).toList();
        int count = ids.size();

        log.info("Synchronous reprocessing started for {} tickets", count);
        ids.forEach(ticketProcessingService::process);
        log.info("Synchronous reprocessing finished for {} tickets", count);

        return ResponseEntity.ok("Reprocessed " + count + " tickets.");
    }

    /**
     * Проверяет что запрос идёт с локального компьютера.
     * 
     * Поддерживает разные форматы IPv4 и IPv6:
     * - 127.0.0.1 — IPv4 localhost
     * - ::1 — IPv6 localhost
     * - 0:0:0:0:0:0:0:1 — IPv6 localhost (полная форма)
     * 
     * @param request HTTP запрос
     * @return true если remoteAddr == localhost, иначе false
     */
    private boolean isLocalhost(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return "127.0.0.1".equals(addr) || "::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr);
    }
}
