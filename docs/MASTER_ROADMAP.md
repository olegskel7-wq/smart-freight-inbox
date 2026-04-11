# Smart Freight Inbox — Master Development Roadmap

> **Назначение:** единый справочный документ по всему проекту.
> Возвращайся сюда при любой потере контекста, перед каждым новым шагом и при передаче задачи другому агенту.

---

## Раздел 1 — Обзор проекта

**Smart Freight Inbox** — учебный проект для университетской защиты.
Система автоматически обрабатывает входящие логистические письма:
принимает почту → извлекает номер контейнера через LLM → ищет контейнер в БД → генерирует черновик ответа → показывает оператору на одобрение.

| Версия | Статус | Описание |
|--------|--------|----------|
| **v1** | В разработке | MVP монолит: IMAP + Async + PostgreSQL + Ollama |
| **v2** | Запланировано | Production: RabbitMQ + Redis + Graph API + Multi-tenant |

### Главное правило

> **v1 код никогда не трогаем при реализации v2.**
> v2 реализуется в изоляции в пакете `com.smartfreight.inbox.v2`.

---

## Раздел 2 — Что уже сделано в v1 (Недели 1–2)

### Стек

- Java 17, **Spring Boot 4.0.5**, Spring Data JPA, Flyway
- PostgreSQL (схема: `V1__init.sql`, демо-данные: `V2__seed_demo_containers.sql`)
- Spring Integration IMAP IDLE (`ImapConfig`, `EmailIngestionService`)
- Ollama HTTP: Qwen 2.5 (извлечение), Llama 3.1 (черновик) — `WebClientConfig`
- Spring Security + JWT валидация (`JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`)
- `@Async` пул (`AsyncConfig`), `TicketProcessingService`
- `RetryScheduler` — повторная обработка `RETRY_LATER` тикетов
- `OllamaHealthIndicator`, Actuator, Prometheus, Grafana (в Docker Compose)
- Testcontainers + один тест: `TicketProcessingServiceTest`

### Структура пакетов (v1)

```
com.smartfreight.inbox
├── InboxApplication
├── actuator/         OllamaHealthIndicator
├── config/           AsyncConfig, ImapConfig, SecurityConfig, WebClientConfig
├── entity/           EmailTicket, Container, User, enums/TicketStatus
├── repository/       EmailTicketRepository, ContainerRepository, UserRepository
├── integration/      EmailIngestionService
├── service/          AiExtractionService, AiDraftingService,
│                     ContainerLookupService, TicketProcessingService
├── scheduler/        RetryScheduler
├── security/         JwtService, JwtAuthenticationFilter
└── exception/        GlobalExceptionHandler, ContainerNotFoundException
```

### Статусы тикета (TicketStatus)

```
NEW → PROCESSING → DRAFT_READY → [оператор закрывает → CLOSED]
                ↘ FAILED
                ↘ RETRY_LATER → PROCESSING (RetryScheduler)
```

### Демо-пользователь (из V2__seed_demo_containers.sql)

- Email: `operator@smartfreight.com`
- Пароль: `demo123` (хранится как BCrypt-хэш)

---

## Раздел 3 — Оставшиеся шаги v1 (Недели 3–4)

### Неделя 3 — REST API (5 шагов, 5 файлов)

---

#### Шаг 1 — `JwtService.java` — добавить `generateToken()`

**Файл:** `src/main/java/com/smartfreight/inbox/security/JwtService.java`
**Тип:** правка существующего файла (additive)
**Что делать:** добавить публичный метод генерации JWT токена.

```java
public String generateToken(String email) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + JWT_EXPIRATION);
    return Jwts.builder()
            .subject(email)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(getSigningKey())
            .compact();
}
```

**Важно:** использовать jjwt 0.12.x API (`Jwts.builder()`, `.subject()`, `.expiration()`).
**Риск поломки:** нет — существующие методы не изменяются.

---

#### Шаг 2 — `TicketStatus.java` — добавить `CLOSED`

**Файл:** `src/main/java/com/smartfreight/inbox/entity/enums/TicketStatus.java`
**Тип:** правка существующего enum (additive)
**Что делать:** добавить новое значение `CLOSED` в конец enum.

```java
CLOSED  // оператор одобрил черновик и закрыл тикет
```

**Риск поломки:** нет — нет switch-выражений без default в существующем коде.

---

#### Шаг 3 — `AuthService.java` — новый файл

**Файл:** `src/main/java/com/smartfreight/inbox/service/AuthService.java`
**Тип:** новый файл
**Что делает:** принимает email + пароль, проверяет в БД, возвращает JWT токен.

Логика:
1. `userRepository.findByEmail(email)` — найти пользователя
2. `passwordEncoder.matches(rawPassword, user.getPassword())` — проверить пароль
3. `jwtService.generateToken(email)` — создать токен
4. Если пользователь не найден или пароль неверный — бросить `ResponseStatusException(401)`

**Зависимости:** `UserRepository`, `PasswordEncoder` (бин из `SecurityConfig`), `JwtService`

---

#### Шаг 4 — `AuthController.java` — новый файл

**Файл:** `src/main/java/com/smartfreight/inbox/controller/AuthController.java`
**Тип:** новый файл
**Эндпоинт:** `POST /api/auth/login`

```
Request:  { "email": "operator@smartfreight.com", "password": "demo123" }
Response: { "token": "eyJhbGci..." }
HTTP 200 OK  /  HTTP 401 если неверные данные
```

`SecurityConfig` уже разрешает `/api/auth/**` без JWT — ничего менять не нужно.

---

#### Шаг 5 — `TicketController.java` — новый файл

**Файл:** `src/main/java/com/smartfreight/inbox/controller/TicketController.java`
**Тип:** новый файл
**Все эндпоинты требуют JWT** (по умолчанию через `SecurityConfig`)

| Метод | URL | Описание | Ответ |
|-------|-----|----------|-------|
| `GET` | `/api/tickets` | Список тикетов (фильтр `?status=DRAFT_READY`) | `List<EmailTicket>` |
| `GET` | `/api/tickets/{id}` | Один тикет с черновиком | `EmailTicket` |
| `POST` | `/api/tickets/{id}/approve` | Одобрить черновик | `{status: "CLOSED"}` |
| `POST` | `/api/tickets/{id}/reject` | Отклонить / перезапустить | `{status: "RETRY_LATER"}` |

**Зависимости:** `EmailTicketRepository`

---

### Неделя 3 — Angular UI (8 шагов, новый проект)

**Расположение:** папка `smart-freight-ui/` рядом с `inbox/` (не внутри Spring Boot)

```
C:\Users\olegs\Downloads\
├── inbox\inbox\          ← Spring Boot бэкенд
└── smart-freight-ui\     ← Angular фронтенд (новый проект)
```

---

#### Шаг 6 — Scaffold Angular проекта

```bash
ng new smart-freight-ui --standalone --routing --style=scss
```

**Версия:** Angular 17+ (standalone компоненты, без NgModules)

---

#### Шаг 7 — `environment.ts`

**Файл:** `src/environments/environment.ts`

```typescript
export const environment = {
  production: false,
  apiUrl: 'http://localhost:8080'
};
```

---

#### Шаг 8 — `AuthService`

**Файл:** `src/app/core/auth.service.ts`

Методы:
- `login(email, password)` → POST `/api/auth/login` → сохранить токен
- `saveToken(token)` → `localStorage.setItem('jwt', token)`
- `getToken()` → `localStorage.getItem('jwt')`
- `isLoggedIn()` → `!!getToken()`
- `logout()` → `localStorage.removeItem('jwt')`

---

#### Шаг 9 — `AuthInterceptor`

**Файл:** `src/app/core/auth.interceptor.ts`

Автоматически добавляет `Authorization: Bearer <token>` к каждому исходящему HTTP-запросу.

---

#### Шаг 10 — `AuthGuard`

**Файл:** `src/app/core/auth.guard.ts`

Перенаправляет на `/login` если `!authService.isLoggedIn()`.

---

#### Шаг 11 — `LoginComponent`

**Файл:** `src/app/login/login.component.ts` + `.html`

- Форма: поля Email + Password
- При успехе → перейти на `/tickets`
- При ошибке → показать сообщение «Неверный email или пароль»

---

#### Шаг 12 — `TicketListComponent`

**Файл:** `src/app/tickets/ticket-list/ticket-list.component.ts` + `.html`

- Таблица тикетов: Subject, Sender, Status, Created At
- Статус-бейдж с цветом (DRAFT_READY = зелёный, FAILED = красный, и т.д.)
- Выпадающий фильтр по статусу
- Клик по строке → `/tickets/{id}`

---

#### Шаг 13 — `TicketDetailComponent`

**Файл:** `src/app/tickets/ticket-detail/ticket-detail.component.ts` + `.html`

- Показывает: тему, отправителя, тело письма, номер контейнера, черновик ответа
- Кнопка **«Одобрить»** → POST `/api/tickets/{id}/approve`
- Кнопка **«Отклонить»** → POST `/api/tickets/{id}/reject`
- После действия → вернуться к списку

---

### Неделя 3 — Grafana provisioning (4 шага)

---

#### Шаг 14 — Datasource provisioning

**Файл:** `monitoring/grafana/provisioning/datasources/prometheus.yml`

Автоматически подключает Prometheus как источник данных при старте Grafana.

---

#### Шаг 15 — Dashboard provisioning config

**Файл:** `monitoring/grafana/provisioning/dashboards/dashboard.yml`

Указывает Grafana загружать JSON-дашборды из папки `/var/lib/grafana/dashboards`.

---

#### Шаг 16 — Dashboard JSON

**Файл:** `monitoring/grafana/dashboards/smartfreight.json`

4 панели:
1. **Tickets by status** — круговая диаграмма `email_ticket` по статусам
2. **Processing duration** — среднее время `NEW → DRAFT_READY`
3. **Retry count** — количество тикетов в `RETRY_LATER`
4. **Ollama health** — статус `/actuator/health` компонента `ollama`

---

#### Шаг 17 — Обновить `docker-compose.yml`

**Файл:** `docker-compose.yml`
**Тип:** только добавление volume mounts к существующему сервису `grafana`

```yaml
grafana:
  volumes:
    - grafana_data:/var/lib/grafana
    - ./monitoring/grafana/provisioning:/etc/grafana/provisioning:ro   # добавить
    - ./monitoring/grafana/dashboards:/var/lib/grafana/dashboards:ro   # добавить
```

---

### Неделя 4 — Тесты (7 шагов)

---

#### Шаг 18 — WireMock dependency в `pom.xml`

**Файл:** `pom.xml`
**Важно:** перед добавлением проверить совместимость с Spring Boot 4.0.5.
Использовать `wiremock-spring-boot` от Maciejwalkowiak или `org.wiremock:wiremock` напрямую.

---

#### Шаг 19 — `AiExtractionServiceTest.java`

**Файл:** `src/test/java/com/smartfreight/inbox/service/AiExtractionServiceTest.java`

Тест-кейсы:
- Ollama возвращает валидный номер → проверить что метод вернул его
- Ollama возвращает мусор → regex fallback нашёл номер в теле
- Ollama недоступен (500) → regex fallback сработал
- Тело письма пустое → вернуть `""`

---

#### Шаг 20 — `AiDraftingServiceTest.java`

**Файл:** `src/test/java/com/smartfreight/inbox/service/AiDraftingServiceTest.java`

Тест-кейсы:
- Ollama возвращает черновик → проверить что текст сохранён
- Ollama недоступен → fallback-черновик (статичный текст)
- Контейнер не найден → черновик содержит сообщение об отсутствии данных

---

#### Шаг 21 — Cucumber dependencies в `pom.xml`

```xml
cucumber-java
cucumber-spring
cucumber-junit-platform-engine
```

**Важно:** использовать версию, совместимую с JUnit 5 и Spring Boot 4.0.5.

---

#### Шаг 22 — `ticket_processing.feature`

**Файл:** `src/test/resources/features/ticket_processing.feature`

```gherkin
Feature: Ticket processing pipeline

  Scenario: Email arrives and gets processed successfully
    Given a new email arrives with container number MSCU1234567
    When the AI pipeline processes the ticket
    Then the ticket status should be DRAFT_READY
    And the draft reply should not be empty

  Scenario: Ollama is unavailable, fallback is used
    Given a new email arrives with container number MSCU1234567
    And Ollama service is unavailable
    When the AI pipeline processes the ticket
    Then the ticket status should be DRAFT_READY
    And the draft reply should contain fallback text
```

---

#### Шаг 23 — `TicketProcessingSteps.java`

**Файл:** `src/test/java/com/smartfreight/inbox/cucumber/TicketProcessingSteps.java`

Step definitions для каждого шага из `.feature` файла.
Использует `@SpringBootTest` + Testcontainers для PostgreSQL + WireMock для Ollama.

---

#### Шаг 24 — `CucumberRunner.java`

**Файл:** `src/test/java/com/smartfreight/inbox/cucumber/CucumberRunner.java`

JUnit 5 runner для запуска Cucumber тестов через Maven (`mvn test`).

---

### Неделя 4 — Финал (2 шага)

---

#### Шаг 25 — `README.md`

**Файл:** `README.md` в корне проекта

Содержание:
- Описание проекта (1 абзац)
- Предварительные требования: Docker, Java 17, Node.js 20+, Angular CLI
- Запуск: `docker compose up --build`
- Первый логин: `operator@smartfreight.com` / `demo123`
- API: базовые примеры curl (login, list tickets, approve)
- Angular: `cd smart-freight-ui && npm install && ng serve`
- Ссылки на Grafana (`:3000`), Prometheus (`:9090`), Actuator (`:8080/actuator/health`)

---

#### Шаг 26 — Git тег v1.0 и финальный push

```bash
git add .
git commit -m "feat: complete v1 MVP"
git tag v1.0
git push origin main --tags
```

Репозиторий: `https://github.com/olegskel7-wq/smart-freight-inbox`

---

## Раздел 4 — План v2 (7 фаз)

> v2 реализуется **после** завершения и защиты v1.
> Весь код v2 — в пакете `com.smartfreight.inbox.v2`.
> **Spring Boot версия: 4.0.5** (не 3.x — проверять совместимость зависимостей).

### Стек v2

| Компонент | Технология |
|-----------|------------|
| Email ingestion | Microsoft Graph API (webhooks + delta) |
| Message broker | **RabbitMQ** (не Kafka) |
| Cache / locks | Redis |
| Multi-tenancy | `tenant_id` в таблицах, JWT claims |
| Resilience | Resilience4j (circuit breaker, retry, bulkhead) |
| Tracing | OpenTelemetry |
| Frontend | Angular 17 (multi-tenant, role-based) |
| DevOps | Docker Compose → Kubernetes (опционально) |

### Структура модулей v2

```
com.smartfreight.inbox.v2
├── config/
│   ├── TenantConfig.java
│   ├── RedisConfig.java
│   ├── RabbitConfig.java
│   ├── GraphApiConfig.java
│   ├── ResilienceConfig.java
│   └── OpenTelemetryConfig.java
├── ingestion/
│   ├── GraphWebhookController.java
│   ├── GraphDeltaService.java
│   └── EmailNormalizationService.java
├── queue/
│   ├── TicketProducer.java
│   ├── TicketConsumer.java
│   └── DeadLetterHandler.java
├── processing/
│   ├── ExtractionWorker.java
│   ├── DraftingWorker.java
│   └── ContainerLookupWorker.java
├── tenant/
│   ├── TenantResolver.java
│   ├── TenantContext.java
│   └── TenantFilter.java
├── security/
│   ├── JwtTenantFilter.java
│   └── RefreshTokenService.java
└── util/
    ├── RateLimiter.java
    └── DistributedLock.java
```

### Фаза 1 — Messaging (RabbitMQ)

**Цель:** заменить `@Async` `ThreadPoolTaskExecutor` на очередь с гарантированной доставкой.

Файлы:
- `RabbitConfig.java` — конфигурация exchange, queue, bindings, DLQ
- `TicketProducer.java` — публикует событие `TicketCreatedEvent` в очередь
- `TicketConsumer.java` — слушает очередь, запускает обработку
- `DeadLetterHandler.java` — обрабатывает сообщения из dead-letter queue

Добавить в `docker-compose.yml`: сервис `rabbitmq:3-management`.

---

### Фаза 2 — Microsoft Graph API

**Цель:** заменить IMAP IDLE на webhooks + delta sync.

Файлы:
- `GraphApiConfig.java` — настройка MSAL (Microsoft Authentication Library)
- `GraphWebhookController.java` — принимает уведомления от Graph
- `GraphDeltaService.java` — запрашивает новые письма через delta query
- `EmailNormalizationService.java` — нормализует Graph-сообщение в формат `EmailTicket`

---

### Фаза 3 — Redis

**Цель:** кэш контейнеров, rate limiting, distributed locks, хранение refresh tokens.

Файлы:
- `RedisConfig.java` — подключение, настройка сериализации
- `DistributedLock.java` — Lua-скрипт через `RedisTemplate` (не обрабатывать один тикет дважды)
- `RateLimiter.java` — ограничение запросов к Ollama
- `RefreshTokenService.java` (в `security/`) — хранить refresh token в Redis с TTL

Добавить в `docker-compose.yml`: сервис `redis:7-alpine`.

---

### Фаза 4 — Multi-tenancy

**Цель:** изолировать данные разных арендаторов (tenant) на уровне БД и JWT.

Файлы:
- `TenantContext.java` — `ThreadLocal<String> tenantId`
- `TenantResolver.java` — извлекает `tenantId` из JWT claim
- `TenantFilter.java` — Servlet filter, устанавливает `TenantContext` для каждого запроса

Изменения в БД: добавить `tenant_id` в таблицы `email_ticket`, `container` (через Flyway миграцию).

---

### Фаза 5 — Resilience4j

**Цель:** сделать вызовы к Ollama отказоустойчивыми.

Файл: `ResilienceConfig.java`

Настройки:
- Circuit breaker: открыть при 50% ошибок за 10 вызовов
- Retry: 3 попытки с backoff 2s → 4s → 8s
- Bulkhead: не более 4 параллельных вызовов к Ollama

Обернуть: `AiExtractionService.extractContainerNumber()` и `AiDraftingService.generateDraft()`.

---

### Фаза 6 — Observability (OpenTelemetry)

**Цель:** сквозная трассировка от приёма письма до ответа оператору.

Файл: `OpenTelemetryConfig.java`

Spans:
- `email.ingestion` → `ticket.queue.publish` → `ticket.processing` → `ai.extraction` → `ai.drafting`

Добавить в `docker-compose.yml`: Jaeger или Zipkin для просмотра трасс.
Обновить Grafana dashboard: добавить панели с данными OpenTelemetry.

---

### Фаза 7 — Angular v2 UI

**Цель:** multi-tenant интерфейс с переключением арендаторов и SLA-дашбордами.

Компоненты (расширяют v1 UI):
- `TenantSwitcherComponent` — выбор активного тенанта
- `SlaOverviewComponent` — среднее время обработки, % в срок
- `UserManagementComponent` — CRUD пользователей тенанта (для ADMIN роли)

Сервисы:
- Обновить `AuthService` — хранить `tenantId` из JWT
- Обновить `AuthInterceptor` — добавлять `X-Tenant-Id` заголовок

---

## Раздел 5 — Правила безопасности (для агентов)

1. **Никогда не трогать v1 пакеты при реализации v2** — всё v2 в `com.smartfreight.inbox.v2`
2. **Версия Spring Boot: 4.0.5** — проверять совместимость любой новой зависимости
3. **Брокер: RabbitMQ** — не Kafka, не ActiveMQ
4. **Изменения `TicketStatus` — только additive** — новые значения добавлять в конец enum
5. **Angular UI — отдельная папка** — не внутри Spring Boot `src/`
6. **`docker-compose.yml` — только additive** — не удалять существующие сервисы и volumes
7. **Flyway миграции — только вперёд** — никогда не редактировать существующие SQL файлы
8. **Тесты используют Testcontainers** — не подключаться к локальной БД в тестах

---

## Раздел 6 — Справочник: порты и сервисы

| Сервис | URL | Логин |
|--------|-----|-------|
| Spring Boot API | http://localhost:8080 | JWT токен |
| Angular UI | http://localhost:4200 | operator@smartfreight.com / demo123 |
| PostgreSQL | localhost:5433 | postgres / postgres |
| Ollama | http://localhost:11434 | — |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | admin / changeme |
| RabbitMQ UI (v2) | http://localhost:15672 | guest / guest |
| Redis (v2) | localhost:6379 | — |

---

*Этот файл — живой документ. Обновляй статусы шагов по мере выполнения.*
*Последнее обновление: начало Недели 3 (REST API не начат).*
