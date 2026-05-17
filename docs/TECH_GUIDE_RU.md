# Техническое руководство SmartFreight

> Документ предназначен для защиты проекта: описание технологий, архитектуры,
> ключевых решений и ответы на вероятные вопросы комиссии.

---

## Содержание

1. [Общее описание системы](#1-общее-описание-системы)
2. [Технологический стек](#2-технологический-стек)
3. [Архитектурная схема системы](#3-архитектурная-схема-системы)
4. [Inbox Service — структура и классы](#4-inbox-service--структура-и-классы)
5. [Port Service — структура и классы](#5-port-service--структура-и-классы)
6. [Схема базы данных](#6-схема-базы-данных)
7. [Потоки обработки данных](#7-потоки-обработки-данных)
8. [Безопасность](#8-безопасность)
9. [Мониторинг и метрики](#9-мониторинг-и-метрики)
10. [Тестирование](#10-тестирование)
11. [Ключевые паттерны и решения](#11-ключевые-паттерны-и-решения)
12. [Вопросы комиссии и ответы](#12-вопросы-комиссии-и-ответы)

---

## 1. Общее описание системы

**SmartFreight** — система автоматической обработки входящей электронной почты
для логистической компании. Система получает письма от клиентов, с помощью
языковой модели (LLM) извлекает номер контейнера, запрашивает его статус
из портового сервиса и автоматически формирует черновик ответного письма.

### Два микросервиса

| Сервис | Порт | Ответственность |
|--------|------|-----------------|
| **inbox** | 8080 | Приём писем, обработка через LLM, веб-UI для оператора |
| **port-service** | 9000 | Хранение и обновление статусов контейнеров |

---

## 2. Технологический стек

### Backend (оба сервиса)

| Технология | Версия | Роль в проекте |
|------------|--------|----------------|
| **Java** | 17 | Язык разработки; использованы records, sealed types, text blocks |
| **Spring Boot** | 3.x | Автоконфигурация, IoC-контейнер, запуск |
| **Spring MVC** | — | Thymeleaf-контроллеры (inbox), REST-контроллеры (port-service) |
| **Spring Data JPA** | — | Репозитории, JPQL-запросы, оптимистичная блокировка |
| **Spring Security** | — | Аутентификация (form login в inbox, API-ключ в port-service) |
| **Spring Integration** | — | IMAP IDLE listener — получение писем без поллинга |
| **Spring Actuator** | — | Эндпоинты /health, /prometheus, /metrics |
| **WebFlux WebClient** | — | Неблокирующий HTTP-клиент для вызовов port-service и Ollama |
| **Micrometer** | — | Библиотека метрик; адаптер для Prometheus |
| **Lombok** | — | Генерация boilerplate (getters, builders, loggers) |

### Персистентность и миграции

| Технология | Роль |
|------------|------|
| **PostgreSQL** | Основная СУБД (две базы: `smartfreight`, `port_system`) |
| **Flyway** | Версионные миграции схемы (V1–V6 / V1–V5) |
| **Hibernate** | ORM-реализация JPA |

### AI / LLM

| Технология | Роль |
|------------|------|
| **Ollama** | Локальный LLM-сервер (без внешних API) |
| **qwen2.5:7b** | Извлечение данных из письма (extraction) |
| **llama3.1:8b** | Составление черновика ответа (drafting) |

### Инфраструктура и мониторинг

| Технология | Роль |
|------------|------|
| **Docker / Docker Compose** | Контейнеризация всех сервисов, единый запуск |
| **Prometheus** | Сбор метрик из `/actuator/prometheus` |
| **Grafana** | Визуализация дашборда метрик |
| **ShedLock** | Распределённая блокировка планировщика (port-service) |

### Frontend

| Технология | Роль |
|------------|------|
| **Thymeleaf** | Server-side HTML шаблоны (inbox UI) |
| **CSRF** | Автоматическая защита форм через `th:action` |

### Тестирование

| Технология | Роль |
|------------|------|
| **JUnit 5** | Фреймворк тестирования |
| **Mockito** | Мокирование зависимостей в unit-тестах |
| **MockMvc** | Тестирование HTTP-слоя без запуска сервера |
| **Testcontainers** | Реальный PostgreSQL в Docker для интеграционных тестов |
| **JaCoCo** | Покрытие кода (gate ≥ 60%) |

---

## 3. Архитектурная схема системы

```
╔══════════════════════════════════════════════════════════════════════════╗
║                        Внешние источники                                 ║
║  ┌──────────────┐        ┌──────────────────┐        ┌───────────────┐  ║
║  │  Email Client │        │   Operator (UI)  │        │  Prometheus   │  ║
║  │  (IMAP IDLE) │        │  browser :8080   │        │  Grafana :3000│  ║
║  └──────┬───────┘        └────────┬─────────┘        └───────┬───────┘  ║
╚═════════╪════════════════════════╪══════════════════════════╪══════════╝
          │ письма                 │ HTTP                     │ /prometheus
          ▼                        ▼                           │
╔══════════════════════════════════════════════════════════════╪══════════╗
║                       inbox service (:8080)                  │          ║
║  ┌─────────────────────────────────────────────────────────────────┐    ║
║  │  EmailIngestionService  ──→  EmailClassificationService (LLM)  │    ║
║  │          │                                                       │    ║
║  │          ▼ afterCommit                                           │    ║
║  │  TicketProcessingService                                         │    ║
║  │    ├─ AiExtractionService  ──→ Ollama qwen2.5:7b               │    ║
║  │    ├─ ContainerLookupService ──→ port-service REST API          │    ║
║  │    └─ AiDraftingService    ──→ Ollama llama3.1:8b              │    ║
║  │                                                                  │    ║
║  │  RetryScheduler (каждые 2 мин)                                  │    ║
║  │  TicketViewController  ──→ Thymeleaf HTML ──→ Operator UI       │    ║
║  │  TicketMetricsBinder   ──→ Micrometer Gauges ─────────────────────→ ║
║  └─────────────────────────────────────────────────────────────────┘    ║
║                         │ X-API-Key                                      ║
╚═════════════════════════╪════════════════════════════════════════════════╝
                          │ HTTP GET /api/containers/{number}
                          ▼
╔════════════════════════════════════════════════════════════════════════╗
║                    port-service (:9000)                                ║
║  ┌─────────────────────────────────────────────────────────────────┐  ║
║  │  ApiKeyFilter  ──→  ContainerController  ──→  ContainerService │  ║
║  │                                                    │             │  ║
║  │  StatusUpdateScheduler (каждые 5 мин)              │             │  ║
║  │    + ShedLock (распределённая блокировка)          ▼             │  ║
║  │                                              ContainerRepository │  ║
║  └─────────────────────────────────────────────────────────────────┘  ║
║                         │                                               ║
╚═════════════════════════╪═══════════════════════════════════════════════╝
                          ▼
            ┌─────────────────────────┐
            │   PostgreSQL port_system│
            │   (port-db контейнер)   │
            └─────────────────────────┘

            ┌─────────────────────────┐
            │   PostgreSQL smartfreight│
            │   (postgres контейнер)  │
            └─────────────────────────┘

            ┌─────────────────────────┐
            │   Ollama (:11434)       │
            │   qwen2.5:7b            │
            │   llama3.1:8b           │
            └─────────────────────────┘
```

### Взаимодействие компонентов в Docker Compose

```
docker-compose.yml
│
├── backend (inbox)      depends_on: postgres, port-service, ollama
├── postgres             smartfreight DB
├── ollama               LLM-сервер + auto pull моделей через entrypoint.sh
├── prometheus           scrape: host.docker.internal:8080/actuator/prometheus
├── grafana              datasource: prometheus; dashboards provisioned
├── port-db              port_system DB
└── port-service         depends_on: port-db
```

---

## 4. Inbox Service — структура и классы

### 4.1 Диаграмма классов и зависимостей

```
InboxApplication
  @SpringBootApplication
  @EnableScheduling
        │
        ├── config/
        │     ├── SecurityConfig ────────── DbUserDetailsService
        │     │     SecurityFilterChain:    UserRepository
        │     │     - form login (/login)
        │     │     - BCryptPasswordEncoder
        │     │     - CSRF включён
        │     │
        │     ├── WebClientConfig ─────────── ollamaWebClient (→ :11434)
        │     │     @Value portServiceUrl        portServiceWebClient (→ :9000)
        │     │     @Value portServiceApiKey     defaultHeader X-API-Key
        │     │
        │     ├── ImapConfig ──────────────── DirectChannel "mailChannel"
        │     │     IntegrationFlow              IMAP IDLE → mailChannel
        │     │
        │     └── SmtpConfig ──────────────── JavaMailSender
        │
        ├── entity/
        │     ├── User          @Entity @Table("app_user")
        │     │                 id, email, password, role
        │     │
        │     ├── EmailTicket   @Entity @SQLDelete @SQLRestriction("deleted=false")
        │     │                 @Version (оптимистичная блокировка)
        │     │                 id, messageId, senderEmail, subject, body,
        │     │                 extractedContainerNumber, status (TicketStatus),
        │     │                 draftReply, retryCount, deleted, version,
        │     │                 createdAt, updatedAt
        │     │
        │     ├── Container     POJO (не @Entity!) — ответ port-service
        │     │                 containerNumber, status, eta, location, updatedAt
        │     │
        │     └── enums/TicketStatus
        │           NEW → PROCESSING → DRAFT_READY
        │                           └→ FAILED → (RETRY_LATER) → NEW
        │                                        └→ CLOSED
        │
        ├── repository/
        │     ├── EmailTicketRepository   JpaRepository<EmailTicket, Long>
        │     │     + findIdsByStatusIn()
        │     │     + resetFailedToNew()
        │     │     + countByStatus(TicketStatus)   ← для Gauge метрик
        │     │
        │     └── UserRepository          findByEmail(String)
        │
        ├── integration/
        │     └── EmailIngestionService
        │           @ServiceActivator(inputChannel = "mailChannel")
        │           @Transactional
        │           ┌─ получает MimeMessage
        │           ├─ классифицирует (LLM) → если IRRELEVANT, игнорирует
        │           ├─ дедупликация по Message-ID
        │           ├─ сохраняет EmailTicket (status=NEW)
        │           └─ afterCommit → ticketProcessingService.process(id)
        │
        ├── service/
        │     ├── TicketProcessingService
        │     │     @Transactional(REQUIRES_NEW)
        │     │     process(Long ticketId):
        │     │       1. status = PROCESSING
        │     │       2. AiExtractionService.extract(body) → containerNumber
        │     │       3. ContainerLookupService.findByNumber(containerNumber)
        │     │       4. AiDraftingService.draft(ticket, container)
        │     │       5. status = DRAFT_READY  / FAILED при ошибке
        │     │       Micrometer: timer, counter DRAFT_READY/FAILED
        │     │
        │     ├── AiExtractionService
        │     │     POST /api/generate → ollamaWebClient
        │     │     model: qwen2.5:7b
        │     │     prompt: "Извлеки номер контейнера из письма..."
        │     │     regex fallback: [A-Z]{4}[0-9]{7}
        │     │
        │     ├── AiDraftingService
        │     │     POST /api/generate → ollamaWebClient
        │     │     model: llama3.1:8b
        │     │     prompt: "Составь вежливый ответ клиенту..."
        │     │     fallback при недоступности LLM
        │     │
        │     ├── ContainerLookupService
        │     │     GET /api/containers/{number} → portServiceWebClient
        │     │     нормализация номера (uppercase, trim)
        │     │     возвращает Optional<Container>
        │     │
        │     ├── EmailReplyService
        │     │     JavaMailSender → SMTP
        │     │     headers: In-Reply-To, References (email threading)
        │     │
        │     ├── EmailClassificationService
        │     │     Ollama → "RELEVANT" / "IRRELEVANT"
        │     │     fail-open: при ошибке считает RELEVANT
        │     │
        │     └── GmailDraftService
        │           IMAP APPEND → папка Drafts
        │
        ├── controller/
        │     ├── TicketViewController  @Controller
        │     │     GET  /              → redirect /tickets
        │     │     GET  /tickets       → список тикетов (Thymeleaf)
        │     │     GET  /tickets/{id}  → детальный вид тикета
        │     │     POST /tickets/{id}/close   → CLOSED
        │     │     POST /tickets/{id}/delete  → мягкое удаление
        │     │     POST /tickets/{id}/draft   → отправить черновик
        │     │     GET  /login         → форма входа
        │     │
        │     └── AdminController  @RestController /admin
        │           POST /admin/reprocess  — запуск повторной обработки
        │           Проверка: request.getRemoteAddr() == loopback only
        │
        ├── scheduler/
        │     └── RetryScheduler
        │           @Scheduled(fixedDelay=120_000, initialDelay=60_000)
        │           Берёт тикеты RETRY_LATER (retryCount < 5)
        │           retryCount++, status=NEW, запускает process(id)
        │           Counter: smartfreight_tickets_retry_count_total
        │
        └── actuator/
              ├── OllamaHealthIndicator
              │     HealthIndicator → GET /api/tags (Ollama)
              │     проверяет наличие обеих моделей
              │
              └── TicketMetricsBinder
                    MeterBinder → Gauge на каждый TicketStatus
                    smartfreight_tickets_current_count{status="NEW"} = repo.countByStatus(NEW)
```

---

## 5. Port Service — структура и классы

### 5.1 Диаграмма классов и зависимостей

```
PortServiceApplication
  @SpringBootApplication
  @EnableScheduling
  @EnableSchedulerLock(defaultLockAtMostFor = "PT6M")
        │
        ├── config/
        │     ├── SecurityConfig
        │     │     CSRF disabled (stateless REST API)
        │     │     SessionCreationPolicy.STATELESS
        │     │     addFilterBefore(ApiKeyFilter, UsernamePasswordAuthFilter)
        │     │     /actuator/health → permitAll
        │     │     все остальные → authenticated()
        │     │
        │     ├── ApiKeyFilter  (OncePerRequestFilter)
        │     │     читает заголовок X-API-Key
        │     │     сравнивает с @Value("${port.api.key}")
        │     │     при совпадении: SecurityContext ← ROLE_API
        │     │
        │     └── ShedLockConfig
        │           LockProvider = JdbcTemplateLockProvider(dataSource)
        │           usingDbTime() — время из БД, не с сервера
        │
        ├── entity/
        │     ├── Container  @Entity @Table("container")
        │     │     id, containerNumber, status (ContainerStatus),
        │     │     eta, location, updatedAt
        │     │
        │     └── ContainerStatus  enum
        │           IN_TRANSIT → AT_PORT → CUSTOMS → RELEASED
        │           → DELIVERED → (цикл начинается снова)
        │
        ├── dto/
        │     └── ContainerResponse  Java record
        │           containerNumber, status, eta, location, updatedAt
        │           @JsonFormat на временных полях
        │           static ContainerResponse from(Container entity)
        │
        ├── repository/
        │     └── ContainerRepository
        │           findByContainerNumber(String)
        │
        ├── service/
        │     └── ContainerService
        │           findByNumber(String number):
        │             1. нормализация (uppercase, trim, удаление пробелов)
        │             2. repo.findByContainerNumber(normalized)
        │             3. map entity → ContainerResponse
        │             4. возвращает Optional<ContainerResponse>
        │
        ├── controller/
        │     └── ContainerController  @RestController
        │           GET /api/containers/{number}
        │             → 200 ContainerResponse (JSON)
        │             → 404 если не найден
        │
        └── scheduler/
              └── StatusUpdateScheduler
                    @Scheduled(fixedDelayString="${port.scheduler.interval-ms:300000}")
                    @SchedulerLock(name="statusUpdateScheduler",
                                   lockAtLeastFor="PT4M",
                                   lockAtMostFor="PT6M")
                    Логика:
                      1. берёт все контейнеры
                      2. случайно выбирает 1–2 из них
                      3. вычисляет nextStatus (циклически)
                      4. сохраняет обновлённый статус
```

---

## 6. Схема базы данных

### 6.1 База данных inbox (`smartfreight`)

```
┌──────────────────────────────────────────────────────────────────────┐
│  app_user                                                            │
├──────────────┬──────────────────────────────────────────────────────┤
│  id          │ BIGSERIAL PRIMARY KEY                                 │
│  email       │ VARCHAR(255) NOT NULL UNIQUE                          │
│  password    │ VARCHAR(255) NOT NULL  ← BCrypt hash                  │
│  role        │ VARCHAR(50)                                           │
└──────────────┴──────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  email_ticket                                                        │
├──────────────────────────────┬───────────────────────────────────────┤
│  id                          │ BIGSERIAL PRIMARY KEY                 │
│  message_id                  │ VARCHAR(255) NOT NULL UNIQUE          │
│  sender_email                │ VARCHAR(255)                          │
│  subject                     │ VARCHAR(500)                          │
│  body                        │ TEXT                                  │
│  extracted_container_number  │ VARCHAR(50)                           │
│  status                      │ VARCHAR(50) NOT NULL DEFAULT 'NEW'    │
│  draft_reply                 │ TEXT                                  │
│  retry_count                 │ INTEGER NOT NULL DEFAULT 0            │
│  deleted                     │ BOOLEAN NOT NULL DEFAULT false        │
│  version                     │ INTEGER NOT NULL DEFAULT 0  ← @Version│
│  created_at                  │ TIMESTAMP NOT NULL DEFAULT now()      │
│  updated_at                  │ TIMESTAMP NOT NULL DEFAULT now()      │
└──────────────────────────────┴───────────────────────────────────────┘

Индексы:
  idx_email_ticket_message_id  ON email_ticket(message_id)
  idx_email_ticket_status      ON email_ticket(status) WHERE deleted = false
```

**Жизненный цикл тикета:**
```
                 ┌──────────────────────────────────────┐
                 ▼                                      │
  NEW ──→ PROCESSING ──→ DRAFT_READY                   │
                    └──→ FAILED ──→ RETRY_LATER ────────┘
                                    (retryCount ≥ 5 → остаётся FAILED)

  DRAFT_READY ──→ CLOSED (оператор одобряет и отправляет)
  любой статус ──→ deleted=true (мягкое удаление, @SQLDelete)
```

### 6.2 База данных port-service (`port_system`)

```
┌──────────────────────────────────────────────────────────────────────┐
│  container                                                           │
├──────────────────┬───────────────────────────────────────────────────┤
│  id              │ BIGSERIAL PRIMARY KEY                             │
│  container_number│ VARCHAR(50) NOT NULL UNIQUE                       │
│  status          │ VARCHAR(50) CHECK (IN IN_TRANSIT, AT_PORT,        │
│                  │   CUSTOMS, RELEASED, DELIVERED)                   │
│  eta             │ TIMESTAMP                                         │
│  location        │ VARCHAR(255)                                      │
│  updated_at      │ TIMESTAMP                                         │
└──────────────────┴───────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  shedlock                                                            │
├──────────────┬───────────────────────────────────────────────────────┤
│  name        │ VARCHAR(64) NOT NULL PRIMARY KEY                      │
│  lock_until  │ TIMESTAMP NOT NULL                                    │
│  locked_at   │ TIMESTAMP NOT NULL                                    │
│  locked_by   │ VARCHAR(255) NOT NULL                                 │
└──────────────┴───────────────────────────────────────────────────────┘
```

---

## 7. Потоки обработки данных

### 7.1 Основной поток: обработка входящего письма

```
[Почтовый ящик IMAP]
        │
        │  новое письмо (push, IDLE)
        ▼
[ImapConfig — IntegrationFlow]
        │
        │  MimeMessage → DirectChannel "mailChannel"
        ▼
[EmailIngestionService.handleEmail()]
        │
        ├─ 1. Классификация через LLM (qwen2.5:7b)
        │      "RELEVANT" / "IRRELEVANT"
        │      Если IRRELEVANT → выход
        │
        ├─ 2. Проверка дублей по Message-ID
        │      Если уже есть → выход
        │
        ├─ 3. Сохранение EmailTicket (status=NEW) в PostgreSQL
        │
        └─ 4. TransactionSynchronizationManager.afterCommit()
                   │
                   ▼
        [TicketProcessingService.process(ticketId)]
                   │
                   ├─ A. status = PROCESSING
                   │
                   ├─ B. AiExtractionService
                   │      POST /api/generate (qwen2.5:7b)
                   │      Prompt: извлечь container number из body
                   │      Regex fallback: [A-Z]{4}[0-9]{7}
                   │      → extractedContainerNumber
                   │
                   ├─ C. ContainerLookupService
                   │      GET /api/containers/{number}
                   │      Header: X-API-Key
                   │      → Optional<Container>
                   │
                   ├─ D. AiDraftingService
                   │      POST /api/generate (llama3.1:8b)
                   │      Prompt: составить ответ с данными о контейнере
                   │      → draftReply
                   │
                   ├─ E. status = DRAFT_READY, сохранение draftReply
                   │      Micrometer timer.record()
                   │      Counter("DRAFT_READY").increment()
                   │
                   └─ при ошибке: status = FAILED
                          Counter("FAILED").increment()
```

### 7.2 Поток ретрая

```
[RetryScheduler] каждые 120 сек (initialDelay 60 сек)
        │
        ├─ EmailTicketRepository.findIdsByStatusIn(RETRY_LATER)
        │   только где retryCount < 5
        │
        ├─ для каждого тикета:
        │   retryCount++
        │   status = NEW
        │   save()
        │
        └─ TicketProcessingService.process(id)
           Counter("retry").increment()
```

### 7.3 Поток работы оператора (веб-UI)

```
[Браузер оператора]
        │
        ├─ GET /tickets
        │   TicketViewController → EmailTicketRepository.findAll()
        │   (deleted=false благодаря @SQLRestriction)
        │   → Thymeleaf → tickets.html (список)
        │
        ├─ GET /tickets/{id}
        │   → ticket-detail.html (детали + черновик ответа)
        │
        ├─ POST /tickets/{id}/draft
        │   EmailReplyService.reply(ticket) → SMTP
        │   status = CLOSED
        │
        ├─ POST /tickets/{id}/close
        │   status = CLOSED (без отправки)
        │
        └─ POST /tickets/{id}/delete
           deleted = true (@SQLDelete: UPDATE SET deleted=true)
```

---

## 8. Безопасность

### 8.1 Inbox — форм-логин + сессия

```
SecurityConfig (inbox)
│
├── CSRF: ВКЛЮЧЁН
│     Thymeleaf th:action автоматически добавляет _csrf токен в формы
│     Защита от межсайтовых запросов (XSRF-атак)
│
├── Form Login:
│     loginPage("/login")
│     usernameParameter("email")  ← не "username"!
│     defaultSuccessUrl("/tickets")
│
├── BCryptPasswordEncoder
│     хэширование паролей при регистрации и проверке
│     cost factor 10 (по умолчанию)
│
├── DbUserDetailsService
│     loadUserByUsername(email) → UserRepository.findByEmail()
│     возвращает Spring UserDetails
│
└── Правила доступа:
      /actuator/**       → permitAll (мониторинг)
      /login             → permitAll
      всё остальное      → authenticated()
```

### 8.2 Port Service — API Key аутентификация

```
SecurityConfig (port-service)
│
├── CSRF: ОТКЛЮЧЁН (stateless REST API, нет сессий)
│
├── SessionCreationPolicy.STATELESS
│     сервер не создаёт и не хранит сессии
│
├── ApiKeyFilter (OncePerRequestFilter)
│     ┌─ читает заголовок X-API-Key из запроса
│     ├─ сравнивает с port.api.key из application.yaml
│     ├─ при совпадении:
│     │   SecurityContextHolder ← UsernamePasswordAuthenticationToken
│     │                            principal="inbox-service"
│     │                            authorities=[ROLE_API]
│     └─ при несовпадении: filterChain.doFilter() без аутентификации
│        → следующий фильтр вернёт 401
│
└── Правила доступа:
      /actuator/health   → permitAll (Docker healthcheck)
      всё остальное      → authenticated()
```

### 8.3 Передача API-ключа

```
docker-compose.yml:
  environment:
    - PORT_SERVICE_API_KEY=${PORT_SERVICE_API_KEY:-smartfreight-port-secret}

application.yaml (inbox):
  port.service.api-key: ${PORT_SERVICE_API_KEY:smartfreight-port-secret}

WebClientConfig (inbox):
  WebClient.builder()
    .baseUrl(portServiceUrl)
    .defaultHeader("X-API-Key", portServiceApiKey)  ← добавляется к каждому запросу
    .build()

application.yaml (port-service):
  port.api.key: ${PORT_SERVICE_API_KEY:smartfreight-port-secret}
```

---

## 9. Мониторинг и метрики

### 9.1 Схема сбора данных

```
inbox service (:8080)
  /actuator/prometheus  ← Micrometer Prometheus Registry
        │
        │  scrape каждые 15 сек
        ▼
Prometheus (:9090)
  хранит time-series данные
        │
        │  PromQL запросы
        ▼
Grafana (:3000)
  провижинированные дашборды (автоматически)
  логин: admin / admin
```

### 9.2 Кастомные метрики проекта

| Метрика | Тип | Описание |
|---------|-----|----------|
| `smartfreight_tickets_current_count{status}` | Gauge | Текущее количество тикетов по каждому статусу |
| `smartfreight_processing_duration_seconds` | Timer | Время обработки тикета (extraction + lookup + drafting) |
| `smartfreight_tickets_status_total{status=DRAFT_READY}` | Counter | Всего успешно обработанных тикетов |
| `smartfreight_tickets_status_total{status=FAILED}` | Counter | Всего тикетов с ошибкой |
| `smartfreight_tickets_retry_count_total` | Counter | Всего повторных попыток обработки |

### 9.3 Реализация TicketMetricsBinder

```java
// Регистрирует Gauge для каждого статуса
for (TicketStatus status : TicketStatus.values()) {
    Gauge.builder("smartfreight_tickets_current_count",
                  emailTicketRepository,
                  repo -> repo.countByStatus(status))  // ← вызывается при каждом scrape
         .tag("status", status.name())
         .register(registry);
}
```

**Почему Gauge, а не Counter?**
- Counter только растёт; не подходит для текущего состояния
- Gauge отражает реальное количество в БД на момент запроса

### 9.4 Дашборд Grafana

| Панель | PromQL |
|--------|--------|
| Tickets by Status (pie) | `sum(smartfreight_tickets_current_count) by (status)` |
| Среднее время обработки | `rate(...duration_sum[1m]) / rate(...duration_count[1m])` |
| Backlog (NEW + RETRY) | `sum(smartfreight_tickets_current_count{status=~"NEW\|RETRY_LATER"})` |
| Retry Count | `sum(smartfreight_tickets_retry_count_total)` |
| Backend Health | `up{job="smart-freight-backend"}` |

### 9.5 OllamaHealthIndicator

```java
// Пример вывода в /actuator/health:
{
  "status": "UP",
  "components": {
    "ollama": {
      "status": "UP",
      "details": {
        "qwen2.5:7b": "loaded",
        "llama3.1:8b": "loaded"
      }
    }
  }
}
```

---

## 10. Тестирование

### 10.1 Стратегия тестирования inbox

```
src/test/
  ├── InboxApplicationTests          ← @SpringBootTest, Testcontainers PostgreSQL
  │     Smoke test: контекст загружается без ошибок
  │
  ├── TestcontainersConfiguration    ← @Bean PostgreSQLContainer
  │     @ServiceConnection           ← Spring Boot автоматически настраивает datasource
  │
  ├── controller/
  │   └── AdminControllerTest        ← MockMvc standalone
  │         - /admin/reprocess с localhost → 200
  │         - /admin/reprocess с внешнего IP → 403
  │
  ├── exception/
  │   └── GlobalExceptionHandlerTest ← проверка HTTP статусов и JSON ответов
  │
  ├── integration/
  │   └── EmailIngestionServiceTest  ← Mockito
  │         - обычное письмо → тикет сохранён
  │         - дубль по Message-ID → пропущен
  │         - multipart письмо → body извлечён
  │         - IRRELEVANT классификация → тикет не создан
  │         - afterCommit вызывает process()
  │
  ├── scheduler/
  │   └── RetrySchedulerTest         ← Mockito
  │         - тикеты RETRY_LATER → обработаны
  │         - retryCount инкрементируется
  │         - пустой список → нет вызовов
  │
  └── service/
      ├── AiExtractionServiceTest    ← Mockito WebClient
      │     - обычный ответ LLM → номер извлечён
      │     - ответ с мусором → regex fallback
      │     - LLM недоступна → пустой Optional
      │
      ├── AiDraftingServiceTest
      │     - черновик составлен
      │     - fallback при ошибке LLM
      │     - контейнер null → общий ответ
      │
      ├── ContainerLookupServiceTest
      │     - найден → ContainerResponse
      │     - нормализация номера (lowercase → uppercase)
      │     - 404 → пустой Optional
      │
      └── EmailReplyServiceTest
            - threading headers (In-Reply-To, References)
            - JavaMailSender вызван с правильными параметрами
```

### 10.2 Стратегия тестирования port-service

```
src/test/
  ├── PortServiceApplicationTests    ← Testcontainers PostgreSQL, context load
  ├── TestcontainersConfiguration    ← postgres:16-alpine, @ServiceConnection
  ├── controller/
  │   └── ContainerControllerTest    ← MockMvc standalone
  │         - GET /api/containers/MSCU1234567 → 200 JSON
  │         - несуществующий номер → 404
  │
  ├── service/
  │   └── ContainerServiceTest       ← Mockito
  │         - найден → ContainerResponse
  │         - нормализация входных данных
  │         - null/blank → IllegalArgumentException
  │         - не найден → пустой Optional
  │
  └── scheduler/
      └── StatusUpdateSchedulerTest  ← Mockito
            - матрица переходов nextStatus (все 5 статусов)
            - случайный выбор 1–2 контейнеров
            - пустой список → нет вызовов save
            - captors → проверка конкретных сохранённых значений
```

### 10.3 JaCoCo coverage gate

```xml
<configuration>
    <rules>
        <rule>
            <limits>
                <limit>
                    <minimum>0.60</minimum>  <!-- 60% покрытие обязательно -->
                </limit>
            </limits>
        </rule>
    </rules>
</configuration>
```

---

## 11. Ключевые паттерны и решения

### 11.1 Транзакционная граница и afterCommit

**Проблема:** После сохранения тикета нужно запустить обработку, но обработка
должна видеть тикет в БД (иначе LLM-вызов провалится по NOT FOUND).

**Решение:**
```java
// EmailIngestionService.java
@Transactional
public void handleEmail(MimeMessage message) {
    // ... save ticket ...
    EmailTicket saved = emailTicketRepository.save(ticket);

    // Запускаем ПОСЛЕ commit, чтобы process() нашёл тикет в БД
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                ticketProcessingService.process(saved.getId());
            }
        }
    );
}
```

### 11.2 Оптимистичная блокировка (`@Version`)

**Проблема:** RetryScheduler и EmailIngestionService могут одновременно
обновлять один тикет в многопоточной среде.

**Решение:**
```java
@Entity
public class EmailTicket {
    @Version
    private Integer version;  // Hibernate автоматически проверяет при UPDATE
}
// Если два потока читают version=3 и оба пытаются UPDATE:
// Первый успешно пишет version=4
// Второй получает OptimisticLockException → rollback
```

### 11.3 Мягкое удаление (`@SQLDelete` + `@SQLRestriction`)

```java
@Entity
@SQLDelete(sql = "UPDATE email_ticket SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")  // автоматически добавляется к КАЖДОМУ SELECT
public class EmailTicket {
    private boolean deleted = false;
}
// findAll() → SELECT ... WHERE deleted = false (автоматически)
// delete(entity) → UPDATE ... SET deleted = true (не удаляет из БД)
```

### 11.4 ShedLock — распределённая блокировка

**Проблема:** При горизонтальном масштабировании port-service каждый экземпляр
запускает `StatusUpdateScheduler` независимо → дублирующиеся обновления.

**Решение:**
```java
@SchedulerLock(
    name = "statusUpdateScheduler",
    lockAtLeastFor = "PT4M",   // держит блокировку минимум 4 минуты
    lockAtMostFor = "PT6M"     // автоматически снимает через 6 минут (защита от зависания)
)
public void updateRandomContainerStatuses() { ... }
```
Блокировка хранится в таблице `shedlock` в БД — доступна всем экземплярам.

### 11.5 IMAP IDLE вместо поллинга

```
Поллинг: каждые N сек → GET /inbox → 200 (писем нет) → повторить
                      → лишняя нагрузка, задержка = N сек

IMAP IDLE: сервер удерживает соединение
           клиент ждёт push-уведомления
           → мгновенная реакция, нет лишних запросов
```

### 11.6 Fail-open классификация

```java
// EmailClassificationService.java
try {
    String response = callOllama(body);
    return response.contains("IRRELEVANT");
} catch (Exception e) {
    log.warn("Classification failed, defaulting to RELEVANT");
    return false;  // fail-open: лучше обработать лишнее письмо, чем пропустить нужное
}
```

### 11.7 WebClient с таймаутами

```java
// WebClientConfig.java
HttpClient httpClient = HttpClient.create()
    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
    .responseTimeout(Duration.ofSeconds(180));   // LLM может думать долго

WebClient ollamaWebClient = WebClient.builder()
    .baseUrl(ollamaUrl)
    .clientConnector(new ReactorClientHttpConnector(httpClient))
    .build();
```

### 11.8 Whitelist сортировки в контроллере

```java
// TicketViewController.java — защита от SQL injection через параметры сортировки
private static final Set<String> ALLOWED_SORT_FIELDS =
    Set.of("createdAt", "status", "senderEmail", "subject");

String sortBy = ALLOWED_SORT_FIELDS.contains(rawSortBy) ? rawSortBy : "createdAt";
// Если пользователь передаёт sortBy="; DROP TABLE...", это игнорируется
```

---

## 12. Вопросы комиссии и ответы

### Общая архитектура

**В: Почему два микросервиса, а не монолит?**

О: Разделение по принципу Single Responsibility. Inbox отвечает за бизнес-логику
(письма, LLM, UI), port-service — за доменные данные (контейнеры, их статусы).
Они могут масштабироваться независимо: если нагрузка на inbox вырастет,
port-service не нужно трогать. Кроме того, port-service в будущем может
обслуживать другие системы (не только inbox).

---

**В: Почему не использовали JWT вместо сессий?**

О: Для серверного UI (Thymeleaf) сессия — правильный выбор: пользователь
работает с браузером, а не API. JWT оправдан, когда есть SPA-клиент или
мобильное приложение. В текущей архитектуре JWT добавил бы сложность без выгоды.

---

**В: Что произойдёт, если Ollama недоступна?**

О: Несколько уровней защиты:
1. Классификация: fail-open → письмо считается релевантным
2. Обработка: при исключении → status=FAILED, Counter("FAILED") инкрементируется
3. RetryScheduler через 2 минуты переставит RETRY_LATER тикеты в NEW (если вручную перевести в RETRY_LATER)
4. OllamaHealthIndicator сигнализирует в /actuator/health

---

**В: Как гарантируется что тикет не обработается дважды?**

О: Три механизма:
1. Дедупликация по `message_id` (UNIQUE constraint) при поступлении
2. `@Version` (оптимистичная блокировка) — два потока не смогут одновременно обновить тикет
3. Статусная машина: `afterCommit` запускает `process()`, который сразу ставит `PROCESSING`

---

**В: Что такое afterCommit и зачем он нужен?**

О: Spring Integration вызывает `handleEmail()` в транзакции. Если мы запустим
`process()` прямо внутри транзакции — она ещё не закоммичена, и `process()` не
найдёт тикет в БД. `afterCommit()` гарантирует, что тикет уже виден в базе до
старта обработки.

---

### База данных

**В: Зачем Flyway, если можно `spring.jpa.hibernate.ddl-auto=create`?**

О: `create` пересоздаёт схему при каждом старте — потеря данных. Flyway ведёт
версионированную историю миграций: каждый скрипт выполняется ровно один раз,
остальные пропускаются. Это стандарт для production-систем.

---

**В: Зачем индекс на `status` с условием `WHERE deleted = false`?**

О: Большинство запросов фильтруют активные тикеты. Partial index (`WHERE deleted = false`)
значительно меньше по размеру и быстрее, чем полный индекс по всей таблице.

---

**В: Что такое мягкое удаление и почему не физическое?**

О: При физическом удалении строка исчезает навсегда — нет аудита, невозможно
восстановить. Мягкое удаление (`deleted=true`) сохраняет историю. `@SQLRestriction`
в Hibernate гарантирует, что удалённые записи не попадают в запросы автоматически.

---

### LLM и AI-интеграция

**В: Почему локальный Ollama, а не OpenAI?**

О: Три причины:
1. **Конфиденциальность**: клиентские письма не покидают инфраструктуру компании
2. **Стоимость**: нет платы за API-вызовы
3. **Независимость**: работает без интернета

---

**В: Как работает извлечение номера контейнера?**

О: Двухуровневый подход:
1. LLM (qwen2.5:7b) понимает контекст: "наш контейнер MSCU1234567 застрял..."
2. Regex fallback (`[A-Z]{4}[0-9]{7}`) — если LLM вернул нечитаемый ответ
Если оба не дали результат — контейнер не найден, но тикет всё равно создаётся
с пустым `extractedContainerNumber`.

---

**В: Почему две разные модели для extraction и drafting?**

О: `qwen2.5:7b` хорошо справляется с извлечением структурированных данных из
текста (малый размер → быстрее). `llama3.1:8b` лучше генерирует связный текст
на нескольких языках. Это позволяет оптимизировать каждый этап независимо.

---

### Тестирование

**В: Зачем Testcontainers, а не H2 in-memory?**

О: H2 — другая СУБД. Она не поддерживает PostgreSQL-специфичный синтаксис
(partial indexes, `@SQLDelete` / `@SQLRestriction`). Testcontainers запускает
реальный PostgreSQL в Docker → тесты максимально приближены к production.

---

**В: Что проверяет `@SpringBootTest` в `InboxApplicationTests`?**

О: Smoke test — полный запуск Spring Context с реальной БД (Testcontainers).
Если какой-то бин не может быть создан (например, ошибка конфигурации, циклическая
зависимость), тест упадёт ещё до запуска. Это "канарейка" для всего проекта.

---

### Мониторинг

**В: Зачем Gauge для метрик тикетов, а не Counter?**

О: Counter только растёт. Если мы закрыли 10 тикетов, counter "DRAFT_READY"
увеличится на 10, но потом останется 10 навсегда — не отражает текущее состояние.
Gauge вызывает `countByStatus()` при каждом scrape → показывает реальное
количество прямо сейчас. Нужен для понимания текущего backlog.

---

**В: Как Prometheus узнаёт, откуда брать метрики?**

О: Из файла `monitoring/prometheus.yml`:
```yaml
scrape_configs:
  - job_name: 'smart-freight-backend'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
```
`host.docker.internal` — специальный DNS, который позволяет контейнеру
обращаться к хосту (где запущен backend при разработке).

---

### Docker и инфраструктура

**В: Почему multi-stage Dockerfile?**

О: Одностадийный образ включает Maven, JDK и все зависимости (~600 MB).
Multi-stage:
- Stage 1: Maven компилирует JAR (временный)
- Stage 2: Только JRE Alpine + JAR (~120 MB)
Производственный образ меньше, быстрее скачивается, меньше attack surface.

---

**В: Что такое ShedLock и когда он нужен?**

О: При горизонтальном масштабировании (несколько экземпляров port-service)
каждый экземпляр независимо запускает `@Scheduled` метод. Без блокировки
один и тот же контейнер может обновиться несколько раз одновременно.
ShedLock использует строку в таблице `shedlock` как distributed lock:
только один экземпляр может выполнять метод в данный момент.

---

**В: Почему `external: true` было убрано из ollama volume?**

О: `external: true` означает, что Docker Compose не управляет lifecycle volume —
он должен существовать заранее (`docker volume create ollama`). На новой машине
сборка упадёт с ошибкой. Без `external: true` Compose создаёт volume сам,
а `entrypoint.sh` автоматически скачивает модели при первом запуске.
