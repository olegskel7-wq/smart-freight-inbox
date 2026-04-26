package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin API контроллер для операций обслуживания (доступен только localhost).
 *
 * Содержит "опасные" операции для разработчиков и администраторов:
 *  - Переобработка неудачных/застрявших тикетов
 *  - (в будущем) очистка данных, ручной перезапуск конвейера и т.п.
 *
 * Защита: все эндпоинты проверяют, что запрос идёт с localhost (127.0.0.1 или ::1).
 * Предположение: в production этот контроллер будет недоступен снаружи
 * (firewall / reverse proxy не пробрасывает порт наружу).
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
     *  1. Ollama был недоступен и много тикетов перешли в FAILED.
     *  2. После исправления Ollama нужно переобработать их.
     *  3. Оператор отклонил черновик (RETRY_LATER) и нужно снова запустить AI.
     *
     * Алгоритм:
     *  1. Проверяем, что запрос идёт с localhost (localhost-only).
     *  2. Bulk-сброс FAILED → NEW в отдельной короткой транзакции
     *     (TicketProcessingService.prepareForRetry — один SQL UPDATE).
     *  3. Получаем ТОЛЬКО id тикетов в статусах NEW и RETRY_LATER
     *     (проекция, чтобы не тянуть в память тяжёлые TEXT-поля).
     *  4. Синхронно запускаем process(id) для каждого тикета в текущем потоке.
     *     Каждый process() работает в своей транзакции (REQUIRES_NEW),
     *     поэтому соединения пула освобождаются после каждого тикета.
     *  5. Возвращаем количество переобработанных тикетов.
     *
     * Важные архитектурные решения:
     *  - На методе НЕТ @Transactional. Если бы он был, контроллер держал бы
     *    одну "жирную" транзакцию на всё время цикла (минуты/часы при тысячах
     *    тикетов и таймауте Ollama 150 с на каждый), монопольно занимая
     *    соединение из пула HikariCP и блокируя остальной трафик приложения.
     *  - Транзакционные границы — на уровне сервисных методов
     *    (prepareForRetry и process(id) с REQUIRES_NEW).
     *
     * Внимание: операция синхронная, при 1000+ тикетов может занять часы.
     * Каждый тикет вызывает Ollama (таймаут 150 с) — запускайте, только если
     * уверены в доступности AI.
     *
     * @param request HTTP-запрос (нужен для проверки remoteAddr)
     * @return 200 OK с количеством переобработанных тикетов, либо 403 если не localhost
     */
    @PostMapping("/reprocess")
    public ResponseEntity<String> reprocessAll(HttpServletRequest request) {
        // Безопасность: разрешаем только localhost (см. комментарий к классу).
        if (!isLocalhost(request)) {
            log.warn("Rejected /admin/reprocess from remote address: {}", request.getRemoteAddr());
            return ResponseEntity.status(403).body("Forbidden: admin endpoints are local-only.");
        }

        // Шаг 1: bulk-сброс FAILED → NEW в отдельной короткой транзакции.
        // После возврата соединение БД сразу возвращается в пул HikariCP.
        int reset = ticketProcessingService.prepareForRetry();

        // Шаг 2: получаем только идентификаторы тикетов, готовых к обработке.
        // Используем проекцию по id, чтобы не загружать в память сущности
        // целиком (поле body может быть до нескольких МБ).
        List<Long> ids = emailTicketRepository.findIdsByStatusIn(
                List.of(TicketStatus.NEW, TicketStatus.RETRY_LATER));

        if (ids.isEmpty()) {
            return ResponseEntity.ok("No tickets to reprocess (reset " + reset + " from FAILED).");
        }

        // Шаг 3: синхронно запускаем обработку для каждого тикета.
        // КРИТИЧНО: метод НЕ помечен @Transactional, поэтому здесь нет
        // объемлющей транзакции. Каждый process() работает в своей собственной
        // транзакции (Propagation.REQUIRES_NEW), и пул соединений не блокируется.
        int count = ids.size();
        log.info("Synchronous reprocessing started for {} ticket(s) (reset {} from FAILED)", count, reset);
        ids.forEach(ticketProcessingService::process);
        log.info("Synchronous reprocessing finished for {} ticket(s)", count);

        return ResponseEntity.ok("Reprocessed " + count + " tickets (reset " + reset + " from FAILED).");
    }

    /**
     * Проверяет, что запрос пришёл с локального компьютера.
     *
     * Поддерживаемые форматы remoteAddr:
     *   - 127.0.0.1            — IPv4 loopback
     *   - ::1                  — IPv6 loopback (краткая форма)
     *   - 0:0:0:0:0:0:0:1      — IPv6 loopback (полная форма)
     *
     * Внимание: метод опирается на HttpServletRequest.getRemoteAddr().
     * Если приложение стоит за reverse-proxy (nginx/traefik), нужно либо
     * настроить server.forward-headers-strategy=NATIVE и доверять X-Forwarded-For,
     * либо ограничить сетевой доступ к /admin/** на уровне прокси.
     *
     * @param request HTTP-запрос
     * @return true если remoteAddr указывает на localhost, иначе false
     */
    private boolean isLocalhost(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return "127.0.0.1".equals(addr) || "::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr);
    }
}
