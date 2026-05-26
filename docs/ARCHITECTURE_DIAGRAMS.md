# Architecture Diagrams — SmartFreight Inbox

> All diagrams use [Mermaid](https://mermaid.js.org/) syntax.  
> Render in GitHub, IntelliJ, VS Code (Mermaid plugin), or [mermaid.live](https://mermaid.live).

---

## 1. System Overview

```mermaid
flowchart TD
    subgraph External
        IMAP["📧 IMAP Server"]
        SMTP["📤 SMTP Server"]
    end

    subgraph inbox ["inbox service :8080"]
        Ingestion["EmailIngestionService<br/>IMAP IDLE listener"]
        Pipeline["TicketProcessingService<br/>AI pipeline orchestrator"]
        UI["Thymeleaf UI<br/>Operator interface"]
        Scheduler["RetryScheduler<br/>every 2 min"]
    end

    subgraph PortSystem ["port-service :9000"]
        PortAPI["ContainerController<br/>REST API"]
        PortDB[("PostgreSQL<br/>port_system")]
        PortScheduler["StatusUpdateScheduler<br/>every 5 min + ShedLock"]
    end

    subgraph AI ["Ollama :11434"]
        Qwen["Qwen 2.5:7b<br/>extraction"]
        Llama["Llama 3.1:8b<br/>drafting"]
    end

    subgraph Monitoring
        Prometheus["Prometheus :9090"]
        Grafana["Grafana :3000"]
    end

    InboxDB[("PostgreSQL<br/>smartfreight")]

    IMAP -->|"IMAP IDLE push"| Ingestion
    Ingestion -->|"afterCommit"| Pipeline
    Pipeline <-->|"POST /api/generate"| Qwen
    Pipeline <-->|"POST /api/generate"| Llama
    Pipeline -->|"GET /api/v1/containers/"| PortAPI
    Pipeline --> InboxDB
    PortAPI <--> PortDB
    PortScheduler --> PortDB
    UI <--> InboxDB
    UI -->|"SMTP reply"| SMTP
    Scheduler --> Pipeline

    inbox -.->|"/actuator/prometheus"| Prometheus
    Prometheus -.-> Grafana
```

---

## 2. Email Processing Pipeline

```mermaid
flowchart TD
    A(["📧 New email\nIMAP IDLE push"]) --> B["EmailIngestionService"]

    B --> C{"System-generated\nemail?"}
    C -->|"Yes — draft header"| Z1(["🚫 Ignore\navoid loop"])
    C -->|"No"| D{"Message-ID\npresent?"}
    D -->|"No"| Z2(["🚫 Ignore"])
    D -->|"Yes"| E["Copy to byte array\nguard vs FolderClosedException"]

    E --> F{"Size > 10 MB?"}
    F -->|"Yes"| Z3(["🚫 Ignore"])
    F -->|"No"| G["EmailClassificationService\nQwen 2.5 — up to 30s"]

    G --> H{"Freight enquiry?"}
    H -->|"IRRELEVANT"| Z4(["🚫 Ignore"])
    H -->|"RELEVANT"| I{"Duplicate?\nfindByMessageId"}
    I -->|"Exists"| Z5(["🚫 Ignore"])
    I -->|"New"| J["Save EmailTicket\nstatus = NEW"]

    J --> K["Register afterCommit hook"]
    K --> L(["Transaction committed\nDB connection released"])
    L --> M["TicketProcessingService.process\nREQUIRES_NEW"]
    M --> N["status = PROCESSING\nsaveAndFlush"]

    N --> O["Step 1 — AiExtractionService\nQwen 2.5:7b — up to 150s"]
    O --> P{"Container number\nfound?"}
    P -->|"LLM clean result"| Q["e.g. MSCU1234567"]
    P -->|"LLM added extra text"| R["Regex in LLM response"] --> Q
    P -->|"LLM failed"| S["Regex in email body"] --> Q
    S -->|"Nothing found"| Q2["identifier = empty"]

    Q --> T["Step 2 — ContainerLookupService\nHTTP GET port-service"]
    Q2 --> T
    T --> U{"Container found?"}
    U -->|"404 or error"| V["container = null\nfail-open: continue"]
    U -->|"200 OK"| W["Container: status, location, ETA"]

    V --> X["Step 3 — AiDraftingService\nLlama 3.1:8b — up to 150s"]
    W --> X

    X --> Y{"Ollama\nresponded?"}
    Y -->|"No"| AB["Static fallback draft"]
    Y -->|"Yes"| AC["Professional reply draft\nin customer's language"]

    AB --> AD["status = DRAFT_READY\nsave draftReply"]
    AC --> AD

    AD --> AG(["👤 Operator reviews\n/tickets UI"])
    AG --> AH{"Decision"}
    AH -->|"Send"| AI["EmailReplyService\nSMTP → customer\nstatus = CLOSED"]
    AH -->|"Close"| AJ(["CLOSED\nno email sent"])
    AH -->|"Snooze"| AK(["RETRY_LATER"])

    AK --> AL["RetryScheduler\nevery 2 min"]
    AL --> AM{"retryCount < 5?"}
    AM -->|"Yes"| AN["retryCount++\nstatus = NEW"] --> M
    AM -->|"No"| AO(["Stay RETRY_LATER\nmanual action needed"])
```

---

## 3. Ticket State Machine

```mermaid
stateDiagram-v2
    [*] --> NEW : Email received and saved

    NEW --> PROCESSING : process() started

    PROCESSING --> DRAFT_READY : AI generated draft
    PROCESSING --> FAILED : Error — all attempts exhausted

    DRAFT_READY --> CLOSED : Operator sent or closed
    DRAFT_READY --> RETRY_LATER : Operator snoozed

    RETRY_LATER --> NEW : RetryScheduler — retryCount < 5
    RETRY_LATER --> RETRY_LATER : retryCount >= 5 — manual action

    CLOSED --> [*] : Soft delete — deleted = true
```

---

## 4. inbox — Package Structure

```mermaid
flowchart TB
    subgraph config ["config/"]
        IC["ImapConfig\nIMAP IDLE → mailChannel"]
        SC["SecurityConfig\nform login + BCrypt + CSRF"]
        WC["WebClientConfig\nollamaWebClient\nportServiceWebClient + X-API-Key"]
        SM["SmtpConfig\nJavaMailSender"]
        DU["DbUserDetailsService\nloadUserByUsername"]
    end

    subgraph integration ["integration/"]
        EIS["EmailIngestionService\n@ServiceActivator on mailChannel"]
    end

    subgraph service ["service/"]
        TPS["TicketProcessingService\norchestrates steps 1–3\nREQUIRES_NEW"]
        AES["AiExtractionService\nQwen 2.5:7b + regex fallback"]
        ADS["AiDraftingService\nLlama 3.1:8b + static fallback"]
        CLS["ContainerLookupService\nHTTP GET port-service"]
        ECS["EmailClassificationService\nRELEVANT / IRRELEVANT — fail-open"]
        ERS["EmailReplyService\nSMTP + In-Reply-To header"]
        GDS["GmailDraftService\nIMAP APPEND to Drafts folder"]
        UMS["UserManagementService\nBCrypt encode + validate"]
        SS["SettingsService\nAppSetting key-value CRUD"]
        TAS["TicketAuditService\nimmutable audit trail"]
        SRL["SendRateLimiter\nper-address throttle"]
    end

    subgraph controller ["controller/"]
        TVC["TicketViewController\nGET /tickets\nPOST /tickets/{id}/draft, close, delete"]
        AC["AdminController\nlocalhost-only reprocess"]
        UC["UserManagementController\nadmin user CRUD"]
        SC2["SettingsController\nGET/POST /settings"]
    end

    subgraph other ["other"]
        RS["RetryScheduler\nfixedDelay=120s\nRETRY_LATER → NEW"]
        OHI["OllamaHealthIndicator\nchecks both models"]
        TMB["TicketMetricsBinder\nGauge per status\nCounters for draft/fail/retry"]
        STR["StuckTicketRecovery\nPROCESSING → NEW on restart"]
    end

    subgraph entity ["entity/"]
        ET["EmailTicket\noptimistic lock @Version\nsoft delete @SQLDelete"]
        US2["User — BCrypt password"]
        AS2["AppSetting — key-value config"]
        TAL["TicketAuditLog — immutable"]
        TS["TicketStatus enum\nNEW PROCESSING DRAFT_READY\nFAILED RETRY_LATER CLOSED"]
    end
```

---

## 5. port-service — Package Structure

```mermaid
flowchart TB
    subgraph config2 ["config/"]
        SC3["SecurityConfig\nstateless REST — CSRF disabled\nApiKeyFilter before password filter"]
        AKF["ApiKeyFilter\nX-API-Key header\ntiming-safe MessageDigest.isEqual"]
        AKV["ApiKeyStartupValidator\nfail-fast on default key in prod"]
        SLC["ShedLockConfig\nJdbcTemplateLockProvider + usingDbTime"]
    end

    subgraph controller2 ["controller/"]
        CC["ContainerController\nGET /api/v1/containers/{number}\n200 ContainerResponse / 404 ProblemDetail"]
    end

    subgraph service2 ["service/"]
        CS["ContainerService\nnormalise: trim + toUpperCase\nreturns Optional ContainerResponse"]
    end

    subgraph entity2 ["entity/"]
        CON["Container — JPA entity"]
        CST["ContainerStatus enum\nIN_TRANSIT → AT_PORT\nCUSTOMS → RELEASED → DELIVERED"]
    end

    subgraph dto ["dto/"]
        CR["ContainerResponse — Java record\nfrom(Container) factory method"]
    end

    subgraph scheduler2 ["scheduler/"]
        SUS["StatusUpdateScheduler\nfixedDelay=5min\nSchedulerLock lockAtLeast=4min\nrandom 1–2 containers — cyclic advance"]
    end

    subgraph exception2 ["exception/"]
        GEH["GlobalExceptionHandler\nRFC 7807 ProblemDetail"]
    end
```

---

## 6. Authentication & Security

```mermaid
sequenceDiagram
    participant B as Browser
    participant SC as SecurityConfig
    participant DU as DbUserDetailsService
    participant DB as PostgreSQL

    Note over B,DB: Form Login — inbox UI

    B->>SC: POST /login with email + password
    SC->>DU: loadUserByUsername(email)
    DU->>DB: SELECT FROM app_user WHERE email = ?
    DB-->>DU: User row with BCrypt hash
    DU-->>SC: UserDetails
    SC->>SC: BCryptPasswordEncoder.matches
    SC-->>B: Set-Cookie JSESSIONID — redirect to /tickets

    Note over B,DB: Subsequent requests

    B->>SC: GET /tickets + Cookie JSESSIONID
    SC->>SC: Resolve session — authenticated
    SC-->>B: 200 tickets.html

    Note over B,DB: Inter-service — inbox to port-service

    participant PS as port-service
    participant AKF as ApiKeyFilter

    B->>PS: GET /api/v1/containers/MSCU1234567 + X-API-Key header
    PS->>AKF: doFilterInternal
    AKF->>AKF: MessageDigest.isEqual — timing-safe
    AKF-->>PS: SecurityContext = ROLE_API
    PS-->>B: 200 ContainerResponse JSON
```

---

## 7. Observability Pipeline

```mermaid
flowchart LR
    subgraph App ["inbox :8080"]
        MB["TicketMetricsBinder\nGauge per status\nCounter draft/fail/retry"]
        TPS2["TicketProcessingService\ntimer.record duration"]
        OHI2["OllamaHealthIndicator\nstatus UP / DOWN"]
        ACT["/actuator/prometheus"]
        MB --> ACT
        TPS2 --> ACT
        OHI2 --> ACT
    end

    ACT -->|"scrape every 15s"| PR["Prometheus :9090"]
    PR -->|"PromQL queries"| GF["Grafana :3000\npre-provisioned dashboard"]

    subgraph Dashboard ["Grafana panels"]
        P1["Tickets by status — pie"]
        P2["Avg processing time"]
        P3["Retry count total"]
        P4["Ollama health UP/DOWN"]
        P5["Backlog NEW + RETRY_LATER"]
    end

    GF --> Dashboard
```

---

## 8. Roadmap — Phase Overview

```mermaid
flowchart LR
    P0["Phase 0 — NOW\nUniversalisation\nTrackedItem\nConfigurable statuses\nConfigurable prompts\nItemLookupService"]
    P1["Phase 1 — Next\nCritical fixes\nRabbitMQ async pipeline\nResilience4j\nKeycloak OAuth2\nRedis"]
    P2["Phase 2\nMulti-tenancy\nPostgreSQL RLS\nPer-tenant config"]
    P3["Phase 3\nInfrastructure\nKubernetes + Terraform\nLoki + Jaeger + Sentry\nCI/CD Blue-Green"]
    P4["Phase 4\nProduct\nReact/Next.js UI\nWebhook API\nSelf-service onboarding"]
    P5["Phase 5\nCompliance\nGDPR/ICO\nAudit log\nHashiCorp Vault\nPentest"]

    P0 --> P1 --> P2 --> P4 --> P5
    P1 --> P3
    P3 --> P4

    style P0 fill:#ff8c42,color:#fff
    style P1 fill:#4a9eff,color:#fff
```

---

## 9. Docker Compose — Service Graph

```mermaid
flowchart TB
    subgraph compose ["Docker Compose — llm_internal network"]
        subgraph app ["Application layer"]
            BE["backend\ninbox :8080"]
            PS2["port-service\n:9000"]
        end

        subgraph data ["Data layer"]
            PG["postgres:16\nsmartfreight DB :5433"]
            PDB["port-db\npostgres:16\nport_system DB :5435"]
            OL["ollama:0.3.14\n:11434\nQwen 2.5 + Llama 3.1"]
        end

        subgraph obs ["Observability layer"]
            PR2["prometheus :9090"]
            GF2["grafana :3000\nauto-provisioned"]
        end
    end

    BE -->|"depends_on"| PG
    BE -->|"depends_on"| PS2
    BE -->|"depends_on"| OL
    PS2 -->|"depends_on"| PDB
    PR2 -->|"scrape /actuator/prometheus"| BE
    GF2 -->|"datasource"| PR2

    style BE fill:#4a9eff,color:#fff
    style PS2 fill:#4a9eff,color:#fff
    style OL fill:#ff8c42,color:#fff
    style PG fill:#336791,color:#fff
    style PDB fill:#336791,color:#fff
```

---

*Last updated: 26 May 2026*
