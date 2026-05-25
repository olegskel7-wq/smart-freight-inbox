# port-service / item-service
## CLAUDE.md — Контекст для AI ассистента

Этот микросервис — часть Universal AI Email Automation Platform.
**Полный контекст проекта:** `C:\Users\olegs\Downloads\inbox\inbox\CLAUDE.md`

---

## Назначение
REST микросервис — backend данных для inbox приложения.
Текущая реализация: контейнеры в порту Felixstowe.
**План:** универсализировать в item-service (любая сфера).

## Endpoint
```
GET /api/v1/items/{identifier}
Защищён: X-API-Key header (timing-safe comparison)
```

## Ключевые файлы
```
ContainerController   → REST endpoint
ContainerService      → нормализация, трансформация
ContainerRepository   → Spring Data JPA
Container (entity)    → JPA entity
ContainerResponse     → DTO (record)
ContainerStatus       → enum: IN_TRANSIT, AT_PORT, CUSTOMS, RELEASED, DELIVERED
StatusUpdateScheduler → симуляция обновлений (каждые 5 мин, ShedLock)
ApiKeyFilter          → timing-safe API Key аутентификация
SecurityConfig        → Spring Security stateless
ShedLockConfig        → distributed lock config
```

## Flyway миграции
```
V1 — схема таблицы + индекс
V2 — seed данных (7 контейнеров)
V3 — расширение данных
V4 — CHECK constraint на status (валидация на уровне БД)
V5 — shedlock таблица
```

## Конфигурация запуска (IntelliJ)
```
PORT_SERVICE_API_KEY=smartfreight-port-secret
DB_HOST=localhost / DB_PORT=5435
VM: -Djava.net.preferIPv4Stack=true
```

## Известные проблемы
- Медленный старт (~20 сек) → решение: Java 25 (lazy-initialization пробовали — не помогло, убрали)

## Сделано в code review (25 мая 2026)
- ✅ findAll() в StatusUpdateScheduler → пагинация PageRequest.of(0, 1000)
- ✅ API версионирован: /api/containers → /api/v1/containers (inbox обновлён синхронно)
- ✅ 404 → RFC 7807 ProblemDetail (ContainerNotFoundException + GlobalExceptionHandler)
- ✅ Actuator закрыт: health (only when-authorized details) + /actuator/prometheus открыты, остальное под X-API-Key
- ✅ Fail-fast при дефолтном ключе dev-local-key в профиле prod (ApiKeyStartupValidator)
- ✅ Убран дублирующий индекс idx_container_number (миграция V6)
- ⚠️ Prometheus НЕ умеет слать кастомный X-API-Key → /actuator/prometheus защищается сетевой изоляцией в проде, не ключом

## Следующий шаг (Phase 0)
Переименовать Container → TrackedItem, сделать статусы конфигурируемыми из БД

*Последнее обновление: 25 мая 2026*
