# Smart Freight Inbox — presentation map & diagrams

**How to use:** copy **Mermaid** blocks into [mermaid.live](https://mermaid.live), Marp, Notion, or GitHub.  
For PowerPoint: export PNG/SVG from mermaid.live.

You can add a university logo and a “risks / metrics” slide next to the Observability block for your committee.

---

## 1. Flow diagrams (recommended for slides)

### A. End-to-end pipeline (v1 MVP)

Mail intake, persistence, async LLM processing, operator handoff.

```mermaid
flowchart TB
  subgraph INGEST["1 — Email ingestion"]
    direction TB
    MS[(Mail server<br/>IMAP)]
    IDLE[IMAP IDLE<br/>Spring Integration]
    SVC[EmailIngestionService]
    DUP{Message-ID<br/>already in DB?}
    MS --> IDLE --> SVC --> DUP
    DUP -->|yes| SKIP[Skip duplicate]
    DUP -->|no| SAVE[Save EmailTicket<br/>status NEW]
  end

  subgraph PROC["2 — AI processing (async thread pool)"]
    direction TB
    TPS[TicketProcessingService<br/>@Async llmTaskExecutor]
    ST1[Set status PROCESSING]
    QW[Qwen 2.5 via Ollama<br/>extract container number]
    CL[Container lookup<br/>PostgreSQL]
    LL[Llama 3.1 via Ollama<br/>draft reply]
    ST2[Set status DRAFT_READY]
    SAVE --> TPS
    TPS --> ST1 --> QW --> CL --> LL --> ST2
  end

  subgraph DATA["3 — Data store"]
    PG[(PostgreSQL<br/>EmailTicket, Container, User)]
    ST1 -.-> PG
    QW -.-> PG
    CL --> PG
    LL -.-> PG
    ST2 --> PG
    SAVE --> PG
  end

  subgraph OPS["4 — Human-in-the-loop"]
    UI[Operator UI / API<br/>future]
    ST2 --> UI
  end

  subgraph RES["Resilience"]
    RL[RETRY_LATER on queue full]
    FL[FAILED on errors]
    TPS -.-> RL
    TPS -.-> FL
  end
```

### B. Simplified linear flow (single slide)

```mermaid
flowchart LR
  A[(IMAP mailbox)] --> B[IMAP IDLE]
  B --> C[Ingestion + dedup]
  C --> D[(PostgreSQL)]
  D --> E[Async LLM pipeline]
  E --> F[Qwen extract]
  F --> G[DB lookup]
  G --> H[Llama draft]
  H --> D
  D --> I[Operator review]
```

### C. EmailTicket state machine

```mermaid
stateDiagram-v2
  [*] --> NEW : email ingested
  NEW --> PROCESSING : worker starts
  RETRY_LATER --> PROCESSING : scheduler retry
  PROCESSING --> DRAFT_READY : success
  PROCESSING --> FAILED : unrecoverable error
  PROCESSING --> RETRY_LATER : transient / queue full
  DRAFT_READY --> [*] : operator handled
  FAILED --> [*] : manual resolution
```

### D. Runtime & observability (Docker stack)

```mermaid
flowchart TB
  subgraph APP["Application"]
    SB[Spring Boot backend :8080]
  end
  subgraph DATA["Data & AI"]
    PG[(PostgreSQL)]
    OL[Ollama + GPU<br/>Qwen / Llama]
  end
  subgraph OBS["Observability"]
    PR[Prometheus]
    GF[Grafana]
    AC[Actuator /health]
  end
  SB --> PG
  SB --> OL
  SB --> AC
  AC --> PR
  PR --> GF
```

### E. Planned v2 delta (context slide)

```mermaid
flowchart LR
  subgraph V1["v1 today"]
    IMAP[IMAP IDLE]
    ASYNC[Async ThreadPoolTaskExecutor]
  end
  subgraph V2["v2 target"]
    GRA[Microsoft Graph]
    RMQ[RabbitMQ]
    RD[(Redis)]
    MT[Multi-tenant]
  end
  IMAP -.->|replace| GRA
  ASYNC -.->|replace| RMQ
  RMQ -.-> RD
  GRA -.-> RMQ
```

---

## 2. Mindmap (v1 + path to v2)

```mermaid
mindmap
  root((Smart Freight<br/>Inbox))
    Business
      Port logistics
      On-premise / GDPR
      Human-in-the-loop
    Ingestion
      IMAP IDLE v1
      Graph API v2
    Processing
      Message-ID deduplication
      Async v1 / queue v2
      Ticket statuses
    Local AI
      Qwen extraction
      Llama drafting
      Ollama
    Data
      PostgreSQL
      Flyway migrations
    Security
      JWT
      Spring Security 6
    Observability
      Actuator
      Prometheus / Grafana
```

---

## 3. Layered architecture (nested view)

*Not a data treemap by area; this is a **layer hierarchy** for an “architecture” slide.*

```mermaid
flowchart TB
  subgraph P["System layers"]
    subgraph L4["Layer 4 — Consumers"]
      U[Operator / UI]
    end
    subgraph L3["Layer 3 — API & security"]
      API[REST API]
      SEC[JWT + Security]
    end
    subgraph L2["Layer 2 — Domain & integrations"]
      ING[Email ingestion]
      PROC[Ticket processing]
      LLM[WebClient → Ollama]
    end
    subgraph L1["Layer 1 — Data & infrastructure"]
      PG[(PostgreSQL)]
      DC[Docker Compose]
      MON[Metrics / health]
    end
  end
  U --> API
  API --> SEC
  SEC --> ING
  SEC --> PROC
  ING --> PG
  PROC --> PG
  PROC --> LLM
  PG --> DC
  LLM --> DC
  MON --> DC
```

---

## 4. Data pipeline (compact slide)

```mermaid
flowchart LR
  M[IMAP mail] --> IDLE[IMAP IDLE]
  IDLE --> SVC[EmailIngestionService]
  SVC --> DB[(EmailTicket)]
  DB --> POOL[Async LLM pool]
  POOL --> Q[Qwen extraction]
  Q --> C[Container lookup]
  C --> L[Llama draft]
  L --> DB
  DB --> OP[Operator]
```

---

## 5. Java package tree (v1 repository)

```
smartfreight.inbox
├── InboxApplication
├── actuator
│   └── OllamaHealthIndicator
├── config
│   ├── AsyncConfig          ← LLM pool, MDC, rejection → RETRY_LATER
│   ├── ImapConfig           ← Spring Integration, mailChannel
│   ├── SecurityConfig
│   └── WebClientConfig      ← Ollama timeouts, connection pool
├── entity
│   ├── EmailTicket
│   ├── Container
│   ├── User
│   └── enums / TicketStatus
├── repository
│   ├── EmailTicketRepository
│   ├── ContainerRepository
│   └── UserRepository
├── integration
│   └── EmailIngestionService
├── service
│   └── TicketProcessingService
├── security
│   ├── JwtService
│   └── JwtAuthenticationFilter
└── exception
    ├── GlobalExceptionHandler
    └── ContainerNotFoundException
```

---

## 6. Repository root (top-level files)

```
inbox/
├── pom.xml
├── docker-compose.yml       ← backend, postgres, ollama, prometheus, grafana
├── docs/
│   ├── V2_ARCHITECTURE_AND_PLAN.md
│   └── PRESENTATION_TREE_MAP.md   ← this file
├── monitoring/
│   └── prometheus.yml
├── scripts/
│   └── entrypoint.sh
└── src/main/
    ├── java/.../com/smartfreight/inbox/...
    └── resources/
        ├── application.yaml
        └── db/migration/V1__init.sql
```

---

## 7. Roadmap v1 → v2 (timeline slide)

```mermaid
timeline
    title Product evolution
    section v1 MVP
      Spring Boot monolith : IMAP IDLE : PostgreSQL : Ollama
    section v2
      Multi-tenant : RabbitMQ : Redis : Microsoft Graph
      Resilience4j : OpenTelemetry : horizontal scaling
```

---

*Extend with committee-specific slides (demo, risks, test strategy) as needed.*
