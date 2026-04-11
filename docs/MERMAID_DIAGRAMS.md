# Smart Freight Inbox — Mermaid диаграммы

> Вставляй каждый блок на [mermaid.live](https://mermaid.live) или в GitHub/Notion.
> Каждая диаграмма — отдельный самодостаточный блок.

---

## 1. Полный поток системы (v1)

```mermaid
flowchart TB
  subgraph INGEST ["1 — Приём почты"]
    MS[(Почтовый\nсервер IMAP)]
    IDLE[IMAP IDLE\nSpring Integration]
    SVC[EmailIngestionService]
    DUP{Message-ID\nуже в БД?}
    MS --> IDLE --> SVC --> DUP
    DUP -->|да| SKIP[Пропустить]
    DUP -->|нет| SAVE[Сохранить EmailTicket\nstatus = NEW]
  end

  subgraph PROC ["2 — AI обработка (async)"]
    TPS[TicketProcessingService\n@Async llmTaskExecutor]
    ST1[Статус → PROCESSING]
    QW[Qwen 2.5 / Ollama\nизвлечь номер контейнера]
    CL[Поиск контейнера\nPostgreSQL]
    LL[Llama 3.1 / Ollama\nгенерация черновика]
    ST2[Статус → DRAFT_READY]
    SAVE --> TPS --> ST1 --> QW --> CL --> LL --> ST2
  end

  subgraph DATA ["3 — Хранилище"]
    PG[(PostgreSQL\nEmailTicket, Container, User)]
    ST1 -.-> PG
    CL --> PG
    ST2 --> PG
    SAVE --> PG
  end

  subgraph OPS ["4 — Оператор"]
    UI[Angular UI\nПросмотр / Одобрить / Отклонить]
    ST2 --> UI
  end

  subgraph RES ["Устойчивость"]
    RL[RETRY_LATER\nRetryScheduler]
    FL[FAILED\nручная обработка]
    TPS -.-> RL
    TPS -.-> FL
  end
```

---

## 2. Машина состояний тикета

```mermaid
stateDiagram-v2
  [*] --> NEW : письмо принято
  NEW --> PROCESSING : обработчик запустился
  RETRY_LATER --> PROCESSING : RetryScheduler
  PROCESSING --> DRAFT_READY : успех
  PROCESSING --> FAILED : неустранимая ошибка
  PROCESSING --> RETRY_LATER : временная ошибка / очередь полна
  DRAFT_READY --> CLOSED : оператор одобрил
  DRAFT_READY --> RETRY_LATER : оператор отклонил
  FAILED --> [*] : ручное решение
  CLOSED --> [*]
```

---

## 3. Слоёная архитектура v1

```mermaid
flowchart TB
  subgraph L4 ["Слой 4 — Потребители"]
    UI[Operator Angular UI]
  end
  subgraph L3 ["Слой 3 — API и безопасность"]
    AC[AuthController\nPOST /api/auth/login]
    TC[TicketController\nGET/POST /api/tickets]
    SEC[JWT Filter + SecurityConfig]
  end
  subgraph L2 ["Слой 2 — Домен и интеграции"]
    ING[EmailIngestionService\nIMAP IDLE]
    TPS[TicketProcessingService\n@Async]
    AI[AiExtractionService\nAiDraftingService]
  end
  subgraph L1 ["Слой 1 — Данные и инфраструктура"]
    PG[(PostgreSQL)]
    OL[Ollama\nQwen + Llama]
    DC[Docker Compose]
    MON[Prometheus / Grafana]
  end

  UI --> AC
  UI --> TC
  AC --> SEC
  TC --> SEC
  SEC --> ING
  SEC --> TPS
  ING --> PG
  TPS --> PG
  TPS --> AI
  AI --> OL
  PG --> DC
  OL --> DC
  MON --> DC
```

---

## 4. Структура пакетов v1 (treemap)

```mermaid
mindmap
  root((com.smartfreight\n.inbox))
    InboxApplication
    actuator
      OllamaHealthIndicator
    config
      AsyncConfig
      ImapConfig
      SecurityConfig
      WebClientConfig
    entity
      EmailTicket
      Container
      User
      enums
        TicketStatus
    repository
      EmailTicketRepository
      ContainerRepository
      UserRepository
    integration
      EmailIngestionService
    service
      AiExtractionService
      AiDraftingService
      ContainerLookupService
      TicketProcessingService
    scheduler
      RetryScheduler
    security
      JwtService
      JwtAuthenticationFilter
    controller
      AuthController
      TicketController
    exception
      GlobalExceptionHandler
      ContainerNotFoundException
```

---

## 5. Последовательность аутентификации

```mermaid
sequenceDiagram
  participant UI as Angular UI
  participant AC as AuthController
  participant AS as AuthService
  participant UR as UserRepository
  participant JS as JwtService

  UI->>AC: POST /api/auth/login\n{email, password}
  AC->>AS: login(email, password)
  AS->>UR: findByEmail(email)
  UR-->>AS: User (BCrypt hash)
  AS->>AS: passwordEncoder.matches()
  alt пароль верный
    AS->>JS: generateToken(email)
    JS-->>AS: JWT (24h)
    AS-->>AC: token
    AC-->>UI: 200 OK {token}
  else пароль неверный
    AS-->>AC: ResponseStatusException 401
    AC-->>UI: 401 Unauthorized
  end

  Note over UI: localStorage.setItem('jwt', token)

  UI->>AC: GET /api/tickets\nAuthorization: Bearer eyJ...
  Note over AC: JwtAuthenticationFilter\nвалидирует подпись + срок
  AC-->>UI: 200 OK [tickets]
```

---

## 6. Навигация Angular UI

```mermaid
flowchart LR
  START([Браузер]) --> GUARD{AuthGuard\nесть токен?}
  GUARD -->|нет| LOGIN["/login\nLoginComponent"]
  GUARD -->|да| LIST["/tickets\nTicketListComponent"]

  LOGIN -->|успех| LIST
  LOGIN -->|ошибка| LOGIN

  LIST -->|выбрать тикет| DETAIL["/tickets/:id\nTicketDetailComponent"]

  DETAIL -->|Одобрить| APPROVE["POST /api/tickets/:id/approve\n→ CLOSED"]
  DETAIL -->|Отклонить| REJECT["POST /api/tickets/:id/reject\n→ RETRY_LATER"]

  APPROVE --> LIST
  REJECT --> LIST
```

---

## 7. Docker Compose инфраструктура

```mermaid
flowchart TB
  subgraph COMPOSE ["Docker Compose — сеть llm_internal"]
    subgraph APP ["Приложение"]
      BE["backend\n:8080"]
    end
    subgraph DATA ["Данные и AI"]
      PG["postgres:16\n:5433"]
      OL["ollama:0.3.14\n:11434\nGPU RTX 5080"]
    end
    subgraph OBS ["Мониторинг"]
      PR["prometheus\n:9090"]
      GF["grafana\n:3000"]
    end
  end

  BE --> PG
  BE --> OL
  BE --> PR
  PR --> GF
  PR -->|scrape /actuator/prometheus| BE
```

---

## 8. Grafana provisioning

```mermaid
flowchart LR
  DC["docker-compose.yml\ngrafana: volumes"] -->|":ro mount"| PROV["monitoring/grafana/\nprovisioning/"]
  PROV --> DS["datasources/\nprometheus.yml\n→ auto-connect Prometheus"]
  PROV --> DBP["dashboards/\ndashboard.yml\n→ auto-load JSONs"]
  DC -->|":ro mount"| DASHDIR["monitoring/grafana/\ndashboards/"]
  DASHDIR --> JSON["smartfreight.json\n4 панели"]
  DS --> GF[Grafana :3000]
  DBP --> GF
  JSON --> GF
```

---

## 9. Дорожная карта v1 (временная шкала)

```mermaid
timeline
  title Smart Freight Inbox — v1 план разработки
  section Неделя 1 (ГОТОВО)
    Фундамент : pom.xml, Docker Compose
    Сущности : EmailTicket, Container, User
    Конфиги : Async, IMAP, Security, WebClient
  section Неделя 2 (ГОТОВО)
    Email pipeline : IMAP IDLE, EmailIngestionService
    AI pipeline : AiExtractionService, AiDraftingService
    Устойчивость : RetryScheduler, TicketStatus
    БД : Flyway V1+V2, seed данные
  section Неделя 3 (СЕЙЧАС)
    REST API : JwtService, AuthService, AuthController, TicketController
    Angular UI : LoginComponent, TicketListComponent, TicketDetailComponent
    Grafana : provisioning, dashboard JSON
  section Неделя 4
    Тесты : WireMock, Cucumber
    Финал : README, git v1.0
```

---

## 10. v1 → v2 эволюция

```mermaid
flowchart LR
  subgraph V1 ["v1 сегодня"]
    IMAP[IMAP IDLE]
    ASYNC[Async ThreadPool]
    SINGL[Один инстанс]
    SJWT[JWT без tenant]
  end
  subgraph V2 ["v2 цель"]
    GRAPH[Microsoft Graph API]
    RMQ[RabbitMQ]
    REDIS[(Redis)]
    MT[Multi-tenant]
    R4J[Resilience4j]
    OT[OpenTelemetry]
  end

  IMAP -.->|заменить| GRAPH
  ASYNC -.->|заменить| RMQ
  RMQ -.-> REDIS
  GRAPH -.-> RMQ
  SINGL -.->|масштабировать| R4J
  SJWT -.->|расширить| MT
  MT -.-> OT
```

---

## 11. Структура пакетов v2 (treemap)

```mermaid
mindmap
  root((com.smartfreight\n.inbox.v2))
    config
      TenantConfig
      RedisConfig
      RabbitConfig
      GraphApiConfig
      ResilienceConfig
      OpenTelemetryConfig
    ingestion
      GraphWebhookController
      GraphDeltaService
      EmailNormalizationService
    queue
      TicketProducer
      TicketConsumer
      DeadLetterHandler
    processing
      ExtractionWorker
      DraftingWorker
      ContainerLookupWorker
    tenant
      TenantResolver
      TenantContext
      TenantFilter
    security
      JwtTenantFilter
      RefreshTokenService
    util
      RateLimiter
      DistributedLock
```

---

## 12. v2 архитектура — полный поток

```mermaid
flowchart TB
  subgraph INGEST2 ["Приём (v2)"]
    MS365[Microsoft 365]
    GW[GraphWebhookController]
    GD[GraphDeltaService]
    EN[EmailNormalizationService]
    MS365 --> GW --> GD --> EN
  end

  subgraph QUEUE ["Очередь (RabbitMQ)"]
    TP[TicketProducer]
    EX[(ticket.exchange)]
    QU[(ticket.queue)]
    DLQ[(dead-letter.queue)]
    TP --> EX --> QU
    QU -->|ошибка| DLQ
  end

  subgraph WORKERS ["Обработчики"]
    TC2[TicketConsumer]
    EW[ExtractionWorker\nQwen]
    CLW[ContainerLookupWorker]
    DW[DraftingWorker\nLlama]
    TC2 --> EW --> CLW --> DW
  end

  subgraph STORE ["Хранилище (v2)"]
    PG2[(PostgreSQL\n+ tenant_id)]
    RD[(Redis\nкэш + locks)]
  end

  EN --> TP
  DW --> PG2
  CLW --> RD
  CLW --> PG2
```

---

## 13. RabbitMQ поток

```mermaid
flowchart LR
  ING[EmailIngestionService] -->|TicketCreatedEvent| TP[TicketProducer]
  TP -->|publish| EX["ticket.exchange\n(Direct)"]
  EX -->|routing key: ticket| QU[("ticket.queue")]
  QU -->|consume| TC[TicketConsumer]
  TC --> PROC[Обработчики]
  QU -->|reject / ttl| DLQ[("dead-letter.queue")]
  DLQ --> DLH[DeadLetterHandler\n→ FAILED]
```

---

## 14. Circuit Breaker (Resilience4j)

```mermaid
stateDiagram-v2
  [*] --> Closed : начало
  Closed --> Open : 50% ошибок\nза 10 вызовов
  Open --> HalfOpen : через 60 сек
  HalfOpen --> Closed : тест успешен
  HalfOpen --> Open : тест провален
  note right of Closed : нормальная работа\nOllama доступен
  note right of Open : все вызовы блокируются\nfallback активен
  note right of HalfOpen : один тестовый вызов
```

---

## 15. OpenTelemetry трассировка (v2)

```mermaid
flowchart LR
  subgraph TRACE ["Trace: обработка одного письма"]
    S1["span: email.ingestion\n(Graph webhook)"]
    S2["span: ticket.queue.publish\n(RabbitMQ)"]
    S3["span: ticket.processing\n(Consumer)"]
    S4["span: ai.extraction\n(Qwen / Ollama)"]
    S5["span: container.lookup\n(PostgreSQL)"]
    S6["span: ai.drafting\n(Llama / Ollama)"]
    S1 --> S2 --> S3 --> S4 --> S5 --> S6
  end
  TRACE --> JAE[Jaeger UI\n:16686]
  TRACE --> GF2[Grafana Tempo]
```

---

## 16. Структура файлов проекта (полное дерево)

```mermaid
mindmap
  root((inbox/\nкорень проекта))
    pom.xml
    docker-compose.yml
    docs
      MASTER_ROADMAP.md
      MASTER_ROADMAP_LT.md
      MERMAID_DIAGRAMS.md
      V2_ARCHITECTURE_AND_PLAN.md
      AGENT_HANDOFF_V1_V2.md
      PRESENTATION_TREE_MAP.md
    monitoring
      prometheus.yml
      grafana
        provisioning
          datasources
          dashboards
        dashboards
          smartfreight.json
    scripts
      entrypoint.sh
      github-push.ps1
    src
      main
        java
          com.smartfreight.inbox
        resources
          application.yaml
          db/migration
            V1__init.sql
            V2__seed_demo_containers.sql
      test
        java
          cucumber
          service
        resources
          features
    smart-freight-ui
      src/app
        core
        login
        tickets
      environments
```

---

*Все диаграммы работают на [mermaid.live](https://mermaid.live), в GitHub README, Notion и Obsidian.*
*Файл: `docs/MERMAID_DIAGRAMS.md`*
