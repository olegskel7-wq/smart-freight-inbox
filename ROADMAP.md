# SmartFreight — Master Roadmap
## Полный план вывода на топ-уровень функционала

_Создан: 27 May 2026. Ориентир: функционал Wisor AI + Levity, с преимуществом Local LLM и UK-фокуса._
_Обновлять после завершения каждой фазы._

---

## Текущее состояние

**Работает:**
```
IMAP IDLE → Qwen 2.5 (extraction) → Llama 3.1 (drafting) → Human review → SMTP
```

**Не хватает до уровня конкурентов:** attachment parsing, rate sheets, auto-send, SLA, analytics, multi-tenancy.

**Ближайший конкурент:** Wisor AI (wisor.ai) — freight email → квота за <60 секунд.  
**Зрелый конкурент:** Levity — enterprise email automation с analytics и feedback loop.  
**Наши преимущества:** Local LLM (GDPR by design), UK-фокус, цена £99–299/мес vs $1000+ у конкурентов.

---

## PHASE 0 — Универсализация ✅ ЗАВЕРШЕНА (07.06.2026)

**Цель:** убрать freight-специфику, сделать конфигурируемым под любую вертикаль.

- [x] Configurable AI prompts из БД (`app_setting` + UI `/settings`, плейсхолдеры `{email}`/`{itemContext}`)
- [x] Abstract `ItemLookupService` interface — пайплайн не зависит от конкретного бэкенда
- [x] `Container` → `TrackedItem`, `ContainerLookupService` → `PortServiceItemLookup`, `extractedContainerNumber` → `extractedIdentifier` (V10)
- [x] Статусы из БД — выполнено по дизайну: `TrackedItem.status` это сквозной `String`

**Решения:**
- `TicketStatus` остаётся enum — это state machine пайплайна, а не конфиг вертикали.
- **port-service НЕ переименовываем** — он остаётся эталонным freight-бэкендом за `ItemLookupService` (имитация реального TMS клиента). Новая вертикаль = новая реализация интерфейса.
- Отложенный долг: ISO 6346 regex-фолбэк в `AiExtractionService`, имя `ContainerNotFoundException`.

> Ветка: `phase0/configurable-ai-prompts`. Дальше — Phase 1.

---

## PHASE 1 — Core Product (1–3 месяца)

**Цель:** первый платящий клиент — видит ценность в первую неделю.

| # | Фича | Приоритет | Усилие | Детали |
|---|------|-----------|--------|--------|
| 1.1 | **PDF/Excel attachment parsing** ✅ DONE (09.06.2026) | 🔴 ВЫСОКИЙ | M | `AttachmentParserService`: PDFBox 3.0.3 (PDF) + POI 5.3.0 (Excel), отказоустойчиво. Колонка `attachment_text` (V11), хранится отдельно от body. Текст вложений идёт и в классификатор (RFQ «see attached» не отбрасывается), и в обе AI-модели (склейка в `TicketProcessingService`). Ветка `phase1/attachment-parsing`. |
| 1.2 | **Rate sheet в БД** ✅ DONE (10.06.2026) | 🔴 ВЫСОКИЙ | M | Таблица `carrier_rate` (V13) + UI `/rates` с загрузкой CSV (upsert по carrier+маршрут, построчный отчёт об ошибках). `RateSheetService.buildRateContext()`: детерминированный подбор — ставка попадает в драфт-промпт, если origin и destination упомянуты в письме (включая текст вложений); просроченные не котируются. В промпт идёт только sell price (rate+маржа), сама маржа LLM не видна. Плейсхолдер `{rateContext}`; если его нет в сохранённом промпте — блок ставок дописывается в конец. Флаг `feature.rate-sheets`. AI-извлечение маршрута — отложено до 1.4. Ветка `phase1/rate-sheets`. |
| 1.3 | **Async AI pipeline** ✅ DONE (11.06.2026) | 🔴 ВЫСОКИЙ | L | Решение: **RabbitMQ** + transactional outbox (`outbox_event`, V14). OutboxRelay (FOR UPDATE SKIP LOCKED + publisher confirms), topic exchange `ticket.events` → `q.ticket.process` + DLQ, идемпотентный consumer 2×prefetch=1 (= OLLAMA_NUM_PARALLEL). Ingestion/RetryScheduler/admin на seam `TicketEventPublisher`. Сессия 2: `StuckTicketSweeper` (RECEIVED/NEW/PROCESSING старше 30 мин → RETRY_LATER), outbox-гейджи, метрики очередей в Grafana, DLQ-админка (`/admin/dlq*`). Сессия 3: классификация (Qwen) с IMAP-потока в consumer — статусы `RECEIVED`/`IRRELEVANT`; на IMAP-потоке LLM-вызовов больше нет, нерелевантные письма сохраняются (аудит фильтра). Ветка `phase1/async-pipeline`. |
| 1.4 | **Email classification (расширенный)** ✅ DONE (11.06.2026) | 🔴 ВЫСОКИЙ | S | Enum `TicketType`: RFQ / STATUS_UPDATE / BOOKING_CONFIRM / INVOICE_QUERY / DOCUMENT_REQUEST / GENERAL. Один вызов Qwen решает и релевантность, и тип (расширен шаг 0 в consumer — второго LLM-вызова нет): ответ IRRELEVANT либо тип. Fail-open → GENERAL. Колонка `ticket_type` (V15, nullable — retry-путь и старые тикеты без типа). Флаг `feature.ticket-type` (выкл → бинарный промпт 1.3). Колонка Type в `/tickets` (сортируемая) и в деталях. Метрика `smartfreight_tickets_type_total{type}`. Тип — seam для 1.5 (SLA по типу) и 1.6. Ветка `phase1/ticket-type`. |
| 1.5 | **SLA таймер на тикетах** ✅ DONE (11.06.2026) | 🟡 СРЕДНИЙ | S | `sla_deadline_at` + `sla_breached` (V16, частичный индекс). Дедлайн = createdAt + окно типа (`sla.minutes.*`: RFQ=2ч, BOOKING_CONFIRM=30мин, STATUS_UPDATE=4ч, default=8ч): при ingest — окно GENERAL (тип неизвестен), после классификации уточняется, у IRRELEVANT снимается. `SlaBreachScheduler` (раз в минуту, bulk UPDATE, updatedAt не трогает — не сбивает sweeper). DRAFT_READY/FAILED = открытые (SLA про ответ). Флаг не сбрасывается (история для отчётов). `feature.sla-timer`, метрика `smartfreight_sla_breached_total`, красный бейдж Breached + сортируемая колонка SLA. Ветка `phase1/sla-timer`. |
| 1.6 | **Auto-send threshold** ✅ DONE (11.06.2026) | 🟡 СРЕДНИЙ | S | Решение: confidence = **детерминированная эвристика**, не LLM self-score (7B-модели некалиброваны): +0.3 тип ≠ GENERAL, +0.2 идентификатор извлечён, +0.4 item найден в backend, +0.1 rate подобран. `ai_confidence` (V17) пишется на каждом драфте даже при выключенной фиче — копится статистика для калибровки порога. Три гейта в настройках: `feature.auto-send` (**первый default-OFF флаг** — фича шлёт письма клиентам), `autosend.types` (allowlist, старт = только STATUS_UPDATE: факты из TMS; RFQ с ценами — через оператора), `autosend.threshold` (0.8). SMTP-вызов строго ПОСЛЕ коммита транзакции пайплайна (нет двойной отправки на optimistic-lock retry); провал SMTP оставляет DRAFT_READY (fallback = оператор). Терминальный статус `AUTO_SENT` (отличим от CLOSED), audit `AUTO_SEND`, метрика `smartfreight_autosend_total{result}` + панель и алерт. UI: секция на `/settings`, сортируемая колонка Conf., read-only детали. Ветка `phase1/auto-send`. |
| 1.7 | **Circuit Breaker (Resilience4j)** ✅ DONE (12.06.2026) | 🟡 СРЕДНИЙ | S | Resilience4j **core программно** (Spring-starter таргетит Boot 3 — риск с Boot 4). Два breaker'а в `ResilienceConfig`: `ollama` (общий на классификацию/extraction/drafting — процесс один) и `portService` (4xx «не найден» = business miss, breaker не открывает). Окно 10 вызовов, порог 50%, открытие после 4 сбоев, probe через 30 сек (`resilience.*.wait-open-seconds`). Решение (выбор Олега из A/B): **breaker open → тикет паркуется в RETRY_LATER** вместо мгновенного fallback-драфта — клиент получает нормальный AI-ответ после восстановления Ollama; retryCount при парковке НЕ сжигается, RetryScheduler пропускает прогоны, пока breaker открыт. Тикет, припаркованный до классификации, дообрабатывается на retry (typed-режим: null type = вердикта не было). Port-service breaker open → прежняя деградация (драфт без данных, fail-fast). Метрики `resilience4j_circuitbreaker_*`, панель Circuit Breakers + алерт CircuitBreakerOpen. Ветка `phase1/circuit-breaker`. |
| 1.8 | **Pagination** ✅ DONE (12.06.2026) | 🟡 СРЕДНИЙ | S | Бóльшая часть оказалась сделана ранее: `/tickets` UI пагинирован с code-review (Page<> + PAGE_SIZE=50 + пагинатор), StatusUpdateScheduler ограничен `PageRequest.of(0, 1000)` (25.05.2026). Реальный хвост — **RetryScheduler**: грузил ВСЕ eligible RETRY_LATER entity (TEXT-поля body/draft_reply до 10 МБ) и брал `subList(0, 10)`. Теперь batch-лимит в SQL (`Pageable`, LIMIT 10) + детерминированный порядок **oldest updatedAt first** (FIFO — свежие парковки не оттесняют старые; раньше порядок был неопределён). Удалены мёртвые перегрузки `findByStatus(status)` / `findByStatus(status, Sort)`. Ветка `phase1/pagination`. |

---

## PHASE 2 — Retention (3–6 месяцев)

**Цель:** SmartFreight становится частью ежедневного операционного процесса — клиент не уходит.

| # | Фича | Приоритет | Усилие | Детали |
|---|------|-----------|--------|--------|
| 2.1 | **Inbox Analytics Dashboard** ✅ DONE (12.06.2026) | 🔴 ВЫСОКИЙ | M | Страница `/analytics` (флаг `feature.analytics`, периоды 7/30/90 дней): KPI-карточки — получено писем (relevant vs junk), отправлено ответов (оператор vs auto), % автоматизации, **avg response time** (от получения до первого SEND/AUTO_SEND, атрибуция по дате отправки), SLA breaches; разбивка по статусам (ссылки на `/tickets`) и **по типам** (без junk, null-тип = Unclassified); **графики Chart.js** (webjar 4.5.0, не CDN — данные не покидают сервер): объём по дням stacked relevant/junk с заполнением пустых дней, confidence-гистограмма 10 корзин (1.0 → верхняя). Тикеты атрибутируются по дате получения, отправки — по audit-событиям SEND/AUTO_SEND. Агрегация в SQL: JPQL GROUP BY проекции + native (FILTER, EXTRACT EPOCH; soft-delete фильтр в native — явно, @SQLRestriction туда не доезжает). |
| 2.2 | **Multi-tenancy (PostgreSQL RLS)** — S1 ✅ DONE (12.06.2026) | 🔴 ВЫСОКИЙ | L | **S1 (фундамент) готова:** таблица `tenant` + `tenant_id` во всех tenant-таблицах (V18, dedup message_id стал per-tenant), **два независимых контура изоляции** — Hibernate `@TenantId` (discriminator) + Postgres RLS (V19, политики по GUC `app.tenant_id`/`app.is_system`, fail-closed). Приложение переведено на непривилегированную роль `smartfreight_app` (суперюзера RLS не фильтрует!), Flyway остался под админом. `TenantContext` (web-фильтр из principal, consumer из payload события, шедулеры в system-режиме), tenantId в outbox-событиях. 7 тестов изоляции, включая native SQL и raw-connection fail-closed. **S2 (next):** `tenant_setting` поверх `app_setting` (prompts/флаги/rates per-tenant) + админка тенантов. **S3:** per-tenant mailbox (динамические IMAP-адаптеры), LLM endpoint. |
| 2.3 | **OAuth2/OIDC (Keycloak)** | 🟡 СРЕДНИЙ | M | Заменить session-based auth. Нужен для multi-tenant и горизонтального масштабирования. |
| 2.4 | **Status Update Automation** | 🟡 СРЕДНИЙ | M | По событиям (departed/arrived/customs cleared/delivered) → автописьмо клиенту. 4–6 писем на отправку = 30–60 мин/день экономии оператора. |
| 2.5 | **Quote follow-up automation** | 🟡 СРЕДНИЙ | S | Отправил квоту → нет ответа через N часов → автоматический friendly follow-up. Wisor делает это. |
| 2.6 | **Redis** | 🟡 СРЕДНИЙ | S | Session store + кэш rate sheets + rate limiting API. |
| 2.7 | **Document request handling** | 🟢 НИЗКИЙ | M | Таможня/партнёр просит документ → AI определяет что нужно → отвечает с вложением или эскалирует. |
| 2.8 | **NL-запросы к данным inbox** | 🟡 СРЕДНИЙ | M | «Спроси свой inbox» на естественном языке: сколько RFQ за месяц, где узкие места, avg response time. Local LLM уже в стеке → почти бесплатно поверх Analytics Dashboard (2.1). Levity делает это («Control Tower»). Killer-демо для onboarding. |

---

## PHASE 3 — Platform (6–12 месяцев)

**Цель:** конкурировать с Wisor AI и Levity на уровне функционала.

| # | Фича | Приоритет | Усилие | Детали |
|---|------|-----------|--------|--------|
| 3.1 | **AI Feedback Loop** | 🔴 ВЫСОКИЙ | L | Оператор исправил ответ → сохраняется как training example. Модель точнее с каждой неделей. Главное retention оружие Levity — чем дольше клиент, тем лучше работает. |
| 3.2 | **React/Next.js UI** | 🟡 СРЕДНИЙ | L | Заменить Thymeleaf. WebSocket real-time обновления тикетов без перезагрузки. |
| 3.3 | **Multi-inbox / Team** | 🟡 СРЕДНИЙ | M | Несколько почтовых ящиков, несколько операторов, shared queue, роли (admin/operator). |
| 3.4 | **CargoWise API integration** | 🟡 СРЕДНИЙ | XL | Двусторонняя синхронизация с TMS. Открывает mid-market. Документация требует customer access. |
| 3.5 | **Webhook API для CRM** | 🟢 НИЗКИЙ | M | Push событий во внешние системы (Salesforce, HubSpot, Zapier). |
| 3.6 | **Quote-to-book conversion tracking** | 🟢 НИЗКИЙ | S | Аналитика: % квот → бронирование. Wisor продаёт это как ROI-метрику. |
| 3.7 | **Invoice query resolution** | 🟢 НИЗКИЙ | M | Клиент спрашивает про счёт → AI находит инвойс, объясняет позиции. |

---

## PHASE 4 — Enterprise & Compliance (12+ месяцев)

| # | Фича | Усилие |
|---|------|--------|
| 4.1 | GDPR/ICO: encryption at rest, data retention, right to erasure | L |
| 4.2 | Audit log (immutable, каждое действие) | M |
| 4.3 | Kubernetes + Terraform | XL |
| 4.4 | Loki + Jaeger + Sentry | M |
| 4.5 | CI/CD Blue-Green | L |
| 4.6 | HashiCorp Vault | M |
| 4.7 | Penetration testing | — |

---

## Сводная дорожная карта

```
Сейчас        Phase 0    Универсализация — завершить
                         Container→TrackedItem, статусы из БД, промпты из БД
↓
1–3 мес       Phase 1    Core Product — первый платящий клиент
                         PDF/Excel parsing, Rate sheets, Async pipeline,
                         SLA таймер, Auto-send threshold, Classification
↓
3–6 мес       Phase 2    Retention — клиент не уходит
                         Analytics dashboard, Multi-tenancy, Status automation,
                         Quote follow-ups, Redis
↓
6–12 мес      Phase 3    Platform — уровень Wisor AI + Levity
                         AI Feedback Loop, React UI, Multi-inbox, CargoWise
↓
12+ мес       Phase 4    Enterprise — NHS, Legal, Finance
                         Full GDPR/ICO compliance, K8s, Vault
```

**Усилие:** S = дни, M = 1–2 недели, L = 3–4 недели, XL = 1–2 месяца.

---

## Конкурентная карта

| Конкурент | Чем похож | Наше преимущество |
|---|---|---|
| **Wisor AI** (wisor.ai) | RFQ email → квота <60 сек | Local LLM, UK-фокус, цена |
| **Levity** (levity.ai) | Email automation для logistics, analytics | SME-friendly, нет cloud lock-in |
| **Wove** | Ранняя стадия, похожий scope | UK vs US, local LLM |
| **Freightmate AI** | Document processing | Email-first, проще для SME |

**Постоянные преимущества SmartFreight:**
- 🔒 **Local LLM** — данные не покидают UK серверы, GDPR by design (единственные на рынке)
- 🇬🇧 **UK-фокус** — post-Brexit customs, UK carriers, BIFA, местный рынок
- 💰 **Цена** — £99–299/мес vs $1000+ у Wisor/Levity (5–10x дешевле для SME)
- ⚡ **Простота** — работает поверх email, нет TMS → запуск за один день

---

## 1.3 Async AI pipeline — завершена (сессии 1–3)

> Решение 10.06.2026: **RabbitMQ**, выбран осознанно вместо Spring Events — ради
> multi-instance готовности (Phase 2) и обучающей ценности. Цена выбора — transactional
> outbox против dual-write и DLQ против poison messages — оплачена в сессии 1.

**Сделано (сессия 1, ветка `phase1/async-pipeline`):**
- Transactional outbox: тикет и событие пишутся в одной транзакции — рассинхрон БД/брокер невозможен;
  упавший брокер не блокирует приём почты (события копятся в outbox)
- OutboxRelay: поллер с `FOR UPDATE SKIP LOCKED` (multi-instance safe) + publisher confirms
- Топология: `ticket.events` (topic) → `q.ticket.process`; 3 попытки → DLX → `q.ticket.process.dlq`
- Consumer: 2 потребителя × prefetch=1 = backpressure к Ollama (= OLLAMA_NUM_PARALLEL в compose)
- Ingestion, RetryScheduler и /admin/reprocess переведены на seam `TicketEventPublisher`

**Сделано (сессия 2, 11.06.2026):**
- `StuckTicketSweeper`: NEW/PROCESSING с updatedAt старше 30 мин (конфиг `sweep.stuck-after-minutes`)
  → bulk-флип в RETRY_LATER; дальше штатный RetryScheduler (retryCount, MAX_RETRIES, outbox).
  Закрывает дыру работающего инстанса: poison-сообщение в DLQ или умерший consumer без рестарта JVM
  (startup-recovery ловит только рестарт)
- Метрики: rabbitmq_prometheus plugin (порт 15692, per-queue гранулярность) + scrape job;
  гейджи `smartfreight_outbox_pending` / `smartfreight_outbox_oldest_pending_age_seconds`;
  ряд панелей в Grafana (глубина очереди/DLQ/in-flight, лаг outbox, publish rate, consumers);
  алерты DeadLetterQueueNotEmpty, OutboxRelayStalled, RabbitMQDown
- DLQ-админка: GET `/admin/dlq` (глубина), POST `/admin/dlq/requeue?limit=` (basicGet → republish
  в work queue → ack; at-least-once), POST `/admin/dlq/purge`

**Сделано (сессия 3, 11.06.2026):**
- Классификация (Qwen) убрана с IMAP-потока: ingestion теперь только парсит письмо, дедуплицирует
  и сохраняет тикет в новом статусе `RECEIVED` + outbox-событие — ни одного LLM-вызова на IMAP IDLE.
  Раньше упавшая Ollama блокировала поток до 30 с на письмо
- Классификация выполняется в RabbitMQ consumer (`processAttempt`, шаг 0): нерелевантное письмо →
  терминальный статус `IRRELEVANT` (запись сохраняется — фильтр стал аудируемым, дедуп работает
  и для спама); релевантное → дальше штатный пайплайн (PROCESSING → DRAFT_READY)
- Retry-путь (NEW/RETRY_LATER) классификацию не повторяет; swept RECEIVED-тикет, вернувшийся
  через NEW, пропускает её осознанно — fail-open, как и при недоступной Ollama
- `RECEIVED` добавлен в sweepable-статусы sweeper'а и в `/admin/reprocess` (ручной unstick)
- Задел под 1.4: точка классификации в consumer — то же место, где появится `ticketType`

**Сделано (1.4, 11.06.2026):**
- `TicketType` (RFQ/STATUS_UPDATE/BOOKING_CONFIRM/INVOICE_QUERY/DOCUMENT_REQUEST/GENERAL),
  колонка `ticket_type` (V15, nullable)
- Один вызов Qwen вместо двух: типизированный промпт возвращает IRRELEVANT либо тип —
  релевантность следует из типа. Fail-open: ошибка/непонятный ответ → relevant + GENERAL
- Флаг `feature.ticket-type` (конвенция plan-gating): выключен → старый бинарный
  RELEVANT/IRRELEVANT промпт, тип не пишется
- Retry-путь по-прежнему пропускает классификацию → такие тикеты без типа (UI: «—»)
- UI: сортируемая колонка Type в `/tickets`, строка Type в деталях тикета
- Метрика `smartfreight_tickets_type_total{type}`; парсинг ответа модели покрыт unit-тестами

**Сделано (1.5, 11.06.2026):**
- `sla_deadline_at`/`sla_breached` (V16) + частичный индекс под опрос шедулера
- Дедлайн всегда от createdAt (часы клиента тикают с получения письма): ingest ставит
  окно GENERAL, шаг 0 после классификации уточняет по типу, IRRELEVANT — снимает
- Окна конфигурируемы: `sla.minutes.*` (RFQ=120, BOOKING_CONFIRM=30, STATUS_UPDATE=240,
  default-minutes=480)
- `SlaBreachScheduler`: раз в минуту bulk UPDATE открытых тикетов с прошедшим дедлайном;
  updatedAt сознательно не трогается (иначе сбивался бы staleness-фильтр sweeper'а);
  флаг не сбрасывается — история для SLA-отчётов. DRAFT_READY/FAILED считаются открытыми
- Флаг `feature.sla-timer`, метрика `smartfreight_sla_breached_total`,
  UI: сортируемая колонка SLA + красный бейдж Breached (список и детали)

### Что сделано в 1.6 (11.06.2026)
- Confidence — детерминированная эвристика из проверяемых фактов пайплайна, НЕ self-score
  модели; пишется на каждом DRAFT_READY независимо от флага (статистика для порога)
- Гейты: флаг (default OFF — единственный такой), allowlist типов (STATUS_UPDATE),
  порог 0.8; всё редактируется на /settings без рестарта
- Отправка после коммита транзакции пайплайна; провал SMTP → тикет остаётся DRAFT_READY
  (+ алерт AutoSendFailures, т.к. Gmail-драфт для eligible-тикетов не создаётся)
- Закрыт хвост 1.5: панель SLA Breaches + алерт SlaBreaches в Grafana/Prometheus

### 12.06.2026 — Phase 1.7 Circuit Breaker ✅
- Resilience4j core (программно, без Spring-starter'а — Boot 4): breaker'ы `ollama` и `portService`
- Ключевое поведение: открытый ollama-breaker паркует тикеты в RETRY_LATER (нормальный AI-драфт
  после восстановления вместо мгновенной пустышки); retry-бюджет при парковке не тратится
- Припаркованные до классификации тикеты получают вердикт на retry (null type в typed-режиме)
- Панель Circuit Breakers в Grafana + алерт CircuitBreakerOpen

### 12.06.2026 — Phase 1.8 Pagination ✅ (Phase 1 ЗАКРЫТА)
- `/tickets` UI и StatusUpdateScheduler уже были пагинированы ранее — реальный хвост был
  в RetryScheduler: полная загрузка всех eligible RETRY_LATER (TEXT до 10 МБ) + subList
- Теперь: batch-лимит в SQL (`Pageable`, LIMIT 10), порядок oldest updatedAt first (FIFO),
  totalElements в логе сохраняет видимость полного бэклога
- Удалены неиспользуемые перегрузки `findByStatus` в EmailTicketRepository

**Phase 1 завершена. 2.1 Analytics Dashboard — готов целиком (KPI + типы + avg response time + графики). Следующий шаг — 2.2 Multi-tenancy** (discovery call может сместить приоритеты).

_Подробности: `C:\Users\olegs\Documents\smartfreight-wiki\wiki\analysis\Master Roadmap.md`_

---

*Создан: 27 May 2026*  
*Последнее обновление: 11 Jun 2026 — 1.6 Auto-send threshold готова (эвристический confidence, гейты на /settings, статус AUTO_SENT); добавлены панель/алерт SLA Breaches*
