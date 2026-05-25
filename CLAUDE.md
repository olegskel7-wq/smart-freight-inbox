# SmartFreight → Universal AI Email Automation Platform
## CLAUDE.md — Контекст для AI ассистента

---

## 👤 Владелец проекта
- **Имя:** Олег
- **Уровень:** Junior/Mid Java разработчик (только что защитил дипломный проект)
- **Цель:** Превратить дипломный проект в коммерческий SaaS продукт для UK рынка
- **Подход:** Разрабатывает сам с помощью Claude

---

## 📦 Что такое этот проект

**Текущее название:** SmartFreight Inbox
**Будущее название:** Universal AI Email Automation Platform

Система автоматической обработки входящих email запросов клиентов:
1. Получает письмо через IMAP IDLE
2. Извлекает ключевой идентификатор через AI (Qwen 2.5)
3. Ищет данные в backend сервисе
4. Генерирует черновик ответа через AI (Llama 3.1)
5. Оператор проверяет и отправляет через SMTP

---

## 🏗️ Архитектура (текущее состояние)

### Два микросервиса:
```
inbox (port 8080)        — главное приложение
port-service (port 9000) — backend данных о контейнерах (будет универсализирован)
```

### Tech Stack:
```
Java 17 (план: перейти на Java 25)
Spring Boot 4.0.5
PostgreSQL 16 (port-service: port 5435, inbox: отдельная БД)
Flyway (миграции)
Ollama — локальный LLM (Qwen 2.5:7b + Llama 3.1:8b)
Spring Security (session-based + API Key для межсервисного взаимодействия)
Spring Integration (IMAP IDLE)
Micrometer + Prometheus + Grafana
ShedLock (distributed scheduler locking)
Testcontainers (integration tests)
Thymeleaf + Bootstrap 5 (UI — план: заменить на React)
Docker Compose
```

### Структура inbox (35 Java файлов):
```
config/     — SmtpConfig, ImapConfig, WebClientConfig, SecurityConfig
service/    — AiExtractionService, AiDraftingService, ContainerLookupService,
              TicketProcessingService, EmailReplyService
controller/ — TicketViewController, AdminController
integration/— EmailIngestionService
scheduler/  — RetryScheduler
health/     — OllamaHealthIndicator
entity/     — EmailTicket, User, Container, TicketStatus
repository/ — EmailTicketRepository, UserRepository
exception/  — GlobalExceptionHandler, ContainerNotFoundException
```

### Структура port-service (8 Java файлов):
```
PortServiceApplication, ContainerController, ContainerService,
ContainerRepository, Container (entity), ContainerResponse (DTO),
ContainerStatus (enum), StatusUpdateScheduler, ShedLockConfig, ApiKeyFilter, SecurityConfig
```

---

## 🔑 Ключевые архитектурные решения

1. **IMAP IDLE** вместо polling — push уведомления, мгновенная реакция
2. **Две LLM модели** — Qwen 2.5 для extraction (точность), Llama 3.1 для drafting (качество текста)
3. **Local LLM (Ollama)** — данные не покидают UK серверы, GDPR by design
4. **Human-in-the-loop** — оператор одобряет каждый ответ
5. **API Key auth** с `MessageDigest.isEqual()` (timing-safe) между сервисами
6. **ShedLock** — защита от параллельного запуска scheduler при нескольких инстансах
7. **Optimistic locking + soft delete** на EmailTicket
8. **Дедупликация по Message-ID** — защита от дублей при переподключении IMAP

---

## 🚀 Стратегия продукта

### Позиционирование:
**"The only AI email automation that keeps your data on UK servers"**

### Целевые сферы UK (в порядке приоритета):
1. **Estate Agents** — быстрый цикл продажи, много однотипных писем, owner принимает решение сам
2. **Lorry/Transport companies** — есть потенциальный клиент прямо сейчас!
3. **Legal/Solicitors** — высокий чек, GDPR критичен, длинный цикл продажи
4. **NHS/Healthcare** — огромный рынок, строгий compliance, очень длинный цикл
5. **Finance/Insurance** — высокий чек, FCA regulated

### Конкурентное преимущество:
- Intercom, Zendesk, Freshdesk — хранят данные в США
- Наш продукт — 100% UK инфраструктура
- NHS, Legal, Finance не могут использовать конкурентов

---

## 📋 Enterprise Roadmap

### Phase 0 — Универсализация (2-3 недели) ← СЛЕДУЮЩИЙ ШАГ
- [ ] Container → TrackedItem, port-service → item-service
- [ ] Статусы конфигурируемые (из БД, не hardcoded enum)
- [ ] AI промпты конфигурируемые per tenant
- [ ] Абстрактный ItemLookupService interface

### Phase 1 — Критические архитектурные исправления (4-5 недель)
- [ ] Async AI Pipeline (RabbitMQ) — синхронный pipeline главная проблема!
- [ ] Circuit Breaker (Resilience4j)
- [ ] OAuth2/OIDC (Keycloak)
- [ ] Redis (session store + cache + rate limiting)
- [ ] Исправить findAll() → пагинация
- [ ] API versioning /api/v1/

### Phase 2 — Multi-tenancy (5-6 недель)
- [ ] Tenant модель в БД
- [ ] Row-Level Security (PostgreSQL RLS)
- [ ] Per-tenant конфигурация (mailbox, промпты, LLM endpoint)

### Phase 3 — Infrastructure (параллельно с Phase 2)
- [ ] Kubernetes
- [ ] Terraform
- [ ] Loki + Jaeger + Sentry
- [ ] CI/CD Blue-Green

### Phase 4 — Product (5-6 недель)
- [ ] React/Next.js вместо Thymeleaf
- [ ] WebSocket real-time
- [ ] Analytics dashboard
- [ ] Webhook API для CRM интеграций
- [ ] Self-service onboarding

### Phase 5 — Compliance (3-4 недели)
- [ ] GDPR/ICO — encryption at rest, data retention, right to erasure
- [ ] Audit log
- [ ] HashiCorp Vault
- [ ] Penetration testing

---

## 👥 Клиенты

### Потенциальный клиент #1
- **Сфера:** Lorry/Transport company (UK)
- **Статус:** Потенциальный, знакомый Олега
- **Что нужно:** Автоматические ответы на запросы о статусе доставки
- **Сложность интеграции:** Средняя (нужно узнать есть ли у них TMS/GPS система)
- **Следующий шаг:** Провести discovery call → после него создать `clients/lorry-company/` структуру

---

## 🖥️ Инфраструктура (текущая — для разработки)

```
PostgreSQL: localhost:5435 (port-service), отдельный порт для inbox
Ollama: localhost:11434
IntelliJ IDEA 2025.3.4
Java 17 (plan: Java 25)
Docker Compose для полного стека
```

### Run Configuration (port-service):
```
PORT_SERVICE_API_KEY=smartfreight-port-secret
DB_HOST=localhost
DB_PORT=5435
DB_USER=postgres
DB_PASSWORD=postgres
SCHEDULER_INTERVAL_MS=300000
VM: -Djava.rmi.server.hostname=127.0.0.1 -Djava.net.preferIPv4Stack=true
```

---

## ⚠️ Известные проблемы

1. **Медленный старт port-service** (~20 сек до баннера) — большой classpath SB4.x
   - Добавлены: lazy-initialization, DevTools, Windows Defender exclusions
   - Решение: перейти на Java 25
2. **Синхронный AI pipeline** — главная архитектурная проблема
3. **Нет Circuit Breaker** на межсервисных вызовах
4. **Session-based auth** не масштабируется горизонтально
5. **findAll() без пагинации** в StatusUpdateScheduler

---

## 📁 Расположение файлов

```
C:\Users\olegs\Downloads\inbox\inbox\               — монорепо (git), главное приложение
C:\Users\olegs\Downloads\inbox\inbox\port-service\  — микросервис данных (подпапка монорепо)
C:\Users\olegs\Desktop\ENTERPRISE_PLAN.md           — план развития
```
> ⚠️ Раньше port-service был отдельной папкой `Downloads\port-service` — удалена 25.05.2026.
> Теперь единственная копия в монорепо. Открывать в IntelliJ именно её.

---

## 💡 Как работать с Олегом

- Олег указывает направление и проблемные места
- Claude реализует и объясняет
- После важных решений — обновлять этот файл
- Язык общения: русский (транслит или кириллица)
- Стиль: прямо, без лишней воды

### ⚠️ В КОНЦЕ КАЖДОЙ СЕССИИ — git push
- Перед завершением работы Claude делает commit + push изменений на GitHub
- Репозиторий: `smart-freight-inbox` (монорепо: inbox + port-service в подпапке)
- docker-compose собирает port-service из `./port-service` — это git/деплой-копия
- ⚠️ Олег разрабатывает port-service в `Downloads/port-service` (IntelliJ), а git-копия в `inbox/inbox/port-service`. Перед push синхронизировать (или открыть в IntelliJ именно git-копию, чтобы не было расхождений)
- Коммит-месседж: коротко по сути изменений

---

*Последнее обновление: 25 мая 2026*
