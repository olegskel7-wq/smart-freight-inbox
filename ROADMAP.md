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
| 1.3 | **Async AI pipeline** 🔶 Сессия 2/3 (11.06.2026) | 🔴 ВЫСОКИЙ | L | Решение: **RabbitMQ** + transactional outbox (`outbox_event`, V14). OutboxRelay (FOR UPDATE SKIP LOCKED + publisher confirms), topic exchange `ticket.events` → `q.ticket.process` + DLQ, идемпотентный consumer 2×prefetch=1 (= OLLAMA_NUM_PARALLEL). Ingestion/RetryScheduler/admin на seam `TicketEventPublisher`. Сессия 2: `StuckTicketSweeper` (NEW/PROCESSING старше 30 мин → RETRY_LATER), outbox-гейджи, метрики очередей в Grafana, DLQ-админка (`/admin/dlq*`). Осталось: классификация с IMAP-потока (сессия 3). Ветка `phase1/async-pipeline`. |
| 1.4 | **Email classification (расширенный)** | 🔴 ВЫСОКИЙ | S | Типы: RFQ / STATUS_UPDATE / BOOKING_CONFIRM / INVOICE_QUERY / DOCUMENT_REQUEST. Маршрутизация в правильный workflow. Поле `ticketType` на EmailTicket. |
| 1.5 | **SLA таймер на тикетах** | 🟡 СРЕДНИЙ | S | Поля `slaDeadlineAt` + `slaBreached` на EmailTicket. Вычислять при ingest по типу письма (RFQ=2ч, Booking=30мин, General=8ч). Красная метка в UI. Продаётся клиенту как гарантия ответа. |
| 1.6 | **Auto-send threshold** | 🟡 СРЕДНИЙ | S | Поле `autoSendThreshold` (0.0–1.0) в настройках тенанта. Если `aiConfidence >= threshold` → отправить автоматически минуя оператора. Открывает "24/7 без оператора". |
| 1.7 | **Circuit Breaker (Resilience4j)** | 🟡 СРЕДНИЙ | S | На вызовы item-service и Ollama. Сейчас нет fallback — при падении Ollama всё зависает. |
| 1.8 | **Pagination** | 🟡 СРЕДНИЙ | S | Заменить findAll() на Page<> в StatusUpdateScheduler и TicketViewController. Падёт на реальных данных. |

---

## PHASE 2 — Retention (3–6 месяцев)

**Цель:** SmartFreight становится частью ежедневного операционного процесса — клиент не уходит.

| # | Фича | Приоритет | Усилие | Детали |
|---|------|-----------|--------|--------|
| 2.1 | **Inbox Analytics Dashboard** | 🔴 ВЫСОКИЙ | M | Кол-во писем по типам, avg response time, % автоматизации, SLA compliance. Убийственная фича для onboarding — клиент видит ROI в цифрах сразу. |
| 2.2 | **Multi-tenancy (PostgreSQL RLS)** | 🔴 ВЫСОКИЙ | L | Tenant model в БД. Row-Level Security. Per-tenant: mailbox, prompts, LLM endpoint, rate sheets. |
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

## Следующий шаг — 1.3 Async AI pipeline (сессии 2–3)

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

**Сессия 3:** классификация с IMAP-потока в consumer (статус RECEIVED) — задел под 1.4 ticketType.

_Подробности: `C:\Users\olegs\Documents\smartfreight-wiki\wiki\analysis\Master Roadmap.md`_

---

*Создан: 27 May 2026*  
*Последнее обновление: 11 Jun 2026 — 1.3 сессия 2 завершена, остался хвост — сессия 3*
