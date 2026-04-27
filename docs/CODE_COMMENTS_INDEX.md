# Smart Freight Inbox — Путеводитель по коду с комментариями для защиты

**Используй эту ссылку как шпаргалку во время защиты**

---

## 🎯 БЫСТРЫЕ ССЫЛКИ НА КЛЮЧЕВЫЕ ФАЙЛЫ

### 1️⃣ АУТЕНТИФИКАЦИЯ И БЕЗОПАСНОСТЬ

| Файл | Зачем | Ключевые части |
|------|-------|----------------|
| **`config/SecurityConfig.java`** | Spring Security конфиг | Line 38-95: `securityFilterChain` (авторизация)<br>Line 112-114: `passwordEncoder` (BCrypt)<br>Line 136-138: `authenticationManager` (для REST-логина) |
| **`config/DbUserDetailsService.java`** | Загрузка юзеров из БД | Line 66-84: `loadUserByUsername` (поиск пользователя)<br>Line 69-71: default роль |
| **`service/AuthService.java`** | REST-логин через Spring Security | Line 70-83: `login` метод (делегирует Spring Security)<br>Line 73-74: `authenticationManager.authenticate` |
| **`controller/AuthController.java`** | REST endpoint для логина | Line 45-56: `POST /api/auth/login`<br>Line 51-54: обработка 401 |

**Комиссия может спросить:**
- "Почему вы выбрали BCrypt?" → `Line 100-104 в SecurityConfig`
- "Как вы защищаетесь от перебора паролей?" → `Line 59-63 в AuthService`
- "Откуда берутся юзеры?" → `Line 3-24 в DbUserDetailsService`

---

### 2️⃣ ОСНОВНОЙ AI-КОНВЕЙЕР

| Файл | Зачем | Ключевые части |
|------|-------|----------------|
| **`service/TicketProcessingService.java`** | Оркестрация 3 шагов AI | Line 65-147: метод `process`<br>Line 74-127: три шага обработки<br>Line 129-136: retry на оптимистической блокировке<br>Line 177-183: `prepareForRetry` для админ-панели |
| **`service/AiExtractionService.java`** | Извлечение номера контейнера | Line 41-106: основной метод<br>Line 56-74: вызов Ollama с таймаутом<br>Line 110-118: regex fallback |
| **`service/AiDraftingService.java`** | Генерация черновика ответа | Line 41-98: основной метод<br>Line 62-77: вызов Ollama<br>Line 122-149: fallback при недоступности AI |
| **`integration/EmailIngestionService.java`** | Точка входа для писем | Line 47-127: `processIncomingEmail`<br>Line 50-63: безопасное копирование письма<br>Line 77-79: дедупликация по Message-ID<br>Line 108-113: запуск обработки после commit |

**Комиссия может спросить:**
- "Как вы справляетесь если AI недоступен?" → `Line 102-105 в AiExtractionService` + `Line 94-97 в AiDraftingService`
- "Как вы защищаетесь от двойной обработки?" → `Line 77-79 в EmailIngestionService`
- "Почему вы не используете асинхронность?" → `Line 23 в TicketProcessingService` (намеренно, по заданию)
- "Что происходит при race condition?" → `Line 129-136 в TicketProcessingService`

---

### 3️⃣ ДАННЫЕ И ХРАНИЛИЩЕ

| Файл | Зачем | Ключевые части |
|------|-------|----------------|
| **`entity/EmailTicket.java`** | JPA сущность письма | Line 12-39: аннотации Spring + Lombok<br>Line 42-45: @Id с IDENTITY<br>Line 48-50: Message-ID для дедупликации<br>Line 68-70: @Enumerated(STRING)<br>Line 102-106: @Version для оптимистической блокировки |
| **`repository/EmailTicketRepository.java`** | Spring Data JPA репозиторий | Line 24-31: готовые методы (генерируются автоматически)<br>Line 51-52: `findIdsByStatusIn` (проекция по id)<br>Line 74-79: `resetFailedToNew` (bulk-update) |
| **`config/ImapConfig.java`** | IMAP IDLE конфигурация | Line 38-40: `mailChannel` (DirectChannel)<br>Line 45-102: `imapIdleFlow` (конвейер данных)<br>Line 72-96: Mail.imapIdleAdapter с настройками |

**Комиссия может спросить:**
- "Почему @Version для оптимистической блокировки?" → `Line 102-106 в EmailTicket`
- "Почему UNIQUE на message_id?" → `Line 18 в EmailTicket`
- "Как вы используете Flyway?" → `src/main/resources/db/migration/`
- "Почему IMAP IDLE вместо polling?" → `Line 70-71 в ImapConfig`

---

### 4️⃣ REST API И КОНФИГУРАЦИЯ

| Файл | Зачем | Ключевые части |
|------|-------|----------------|
| **`controller/TicketController.java`** | REST API для тикетов | Line: методы GET/POST тикетов |
| **`controller/AdminController.java`** | Admin endpoints (localhost-only) | Line 76-108: `POST /admin/reprocess`<br>Line 80-82: проверка localhost<br>Line 84-92: архитектура без @Transactional<br>Line 126-129: проверка IPv4 и IPv6 |
| **`controller/TicketViewController.java`** | Thymeleaf UI для браузера | Line 67-91: `GET /tickets` (список с фильтром/сортировкой)<br>Line 112-117: `GET /tickets/{id}`<br>Line 139-179: `POST /tickets/{id}/draft` (редактирование черновика) |
| **`exception/GlobalExceptionHandler.java`** | Обработка исключений | Line 25: `@RestControllerAdvice(annotations = RestController.class)` — ограничение scope<br>Line 29-44: обработчики ошибок |
| **`config/WebClientConfig.java`** | Настройки HTTP-клиентов | Line 37-46: пул соединений для Ollama<br>Line 50-62: таймауты (150 сек для LLM)<br>Line 73-85: короткие таймауты для port-service |

**Комиссия может спросить:**
- "Как вы обрабатываете ошибки в REST API?" → `exception/GlobalExceptionHandler.java`
- "Почему таймаут 150 сек?" → `Line 56-58 в WebClientConfig`
- "Почему localhost-only для /admin?" → `Line 78-82 в AdminController`

---

### 5️⃣ МЕТРИКИ И МОНИТОРИНГ

| Файл | Зачем | Ключевые части |
|------|-------|----------------|
| **`service/TicketProcessingService.java`** | Prometheus метрики | Line 49-59: создание Timer и Counter<br>Line 125: запись времени<br>Line 124: инкремент счётчика |
| **`actuator/OllamaHealthIndicator.java`** | Health check для Ollama | Line: проверка доступности AI |

**Комиссия может спросить:**
- "Как вы мониторите производительность?" → `Line 49-59, 125, 124 в TicketProcessingService`
- "Что такое Micrometer?" → Out-of-the-box Spring Boot компонент для Prometheus

---

## 📋 АРХИТЕКТУРНЫЕ РЕШЕНИЯ И "ИЗ КОРОБКИ" КОМПОНЕНТЫ

### Spring Security (аутентификация)
```
Что использовали: Spring Security @EnableWebSecurity, SecurityFilterChain, BCryptPasswordEncoder
Почему: Production-ready, secure by default, 15+ лет истории
Файлы: SecurityConfig.java, DbUserDetailsService.java
```

### Spring Data JPA (ORM)
```
Что использовали: JpaRepository, @Entity, @Query, @Modifying
Почему: Type-safe запросы, автоматическое CRUD, кроссплатформенность
Файлы: EmailTicketRepository.java, entity/*
```

### Spring Integration (IMAP IDLE)
```
Что использовали: IntegrationFlow, Mail.imapIdleAdapter, @ServiceActivator, MessageChannel
Почему: Не нужно писать reconnect-логику, асинхронная обработка из коробки
Файлы: ImapConfig.java, EmailIngestionService.java
```

### Spring Security + Micrometer (метрики)
```
Что использовали: Actuator, Timer, Counter, /actuator/prometheus endpoint
Почему: Production monitoring из коробки, интеграция с Prometheus/Grafana
Файлы: TicketProcessingService.java, application.yaml
```

### Flyway (миграции БД)
```
Что использовали: Flyway версионирование и auto-run при старте
Почему: Версионирование БД как Git, документирование schema changes
Файлы: src/main/resources/db/migration/V*.sql
```

### Lombok (код-генерация)
```
Что использовали: @Getter, @Setter, @Builder, @NoArgsConstructor
Почему: Убирает boilerplate (50 строк getters/setters = 1 строка), standard в modern Java
Файлы: entity/EmailTicket.java, config/SecurityConfig.java
```

---

## 🛡️ КЛЮЧЕВЫЕ ТЕХНИКИ

### 1. Дедупликация (UNIQUE constraint)
```java
// File: entity/EmailTicket.java, Line 18
@UniqueConstraint(columnNames = "message_id")
```
**Комментарий:** Два письма с одинаковым Message-ID не могут быть сохранены одновременно (БД отклонит).

### 2. Оптимистическая блокировка (защита race condition)
```java
// File: entity/EmailTicket.java, Line 105-106
@Version
private Integer version;
```
**Комментарий:** Если два потока одновременно читают версию 5, только первый успешно сохранит. Второй получит exception и сделает retry.

### 3. Soft delete (GDPR)
```java
// File: entity/EmailTicket.java, Line 35, 39
@SQLDelete(sql = "UPDATE email_ticket SET deleted = true WHERE id=? AND version=?")
@SQLRestriction("deleted=false")
```
**Комментарий:** DELETE → UPDATE SET deleted=true. История остаётся в БД, видны только активные записи.

### 4. Pass ID, not entity (паттерн)
```java
// File: service/TicketProcessingService.java, Line 76
EmailTicket ticket = emailTicketRepository.findById(ticketId)
    .orElseThrow(...);
```
**Комментарий:** Транзакция получает только ID (не сущность), затем перечитывает в новой транзакции. Избегаем LazyInitializationException.

### 5. TransactionSynchronization (afterCommit callback)
```java
// File: integration/EmailIngestionService.java, Line 108-113
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        ticketProcessingService.process(ticketId);
    }
});
```
**Комментарий:** Запускаем обработку ПОСЛЕ того, как письмо сохранено и commit выполнен. Соединение БД в пул вернулось, мы не держим его.

### 6. Retry logic с @Transactional(REQUIRES_NEW)
```java
// File: service/TicketProcessingService.java, Line 64, 72-143
@Transactional(propagation = Propagation.REQUIRES_NEW)
for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
    try {
        // обработка
    } catch (ObjectOptimisticLockingFailureException e) {
        // retry
    }
}
```
**Комментарий:** Каждый retry = новая транзакция. Если версия изменилась, перечитываем с новой версией.

### 7. WebClient с таймаутами
```java
// File: config/WebClientConfig.java, Line 56-58
.addHandlerLast(new ReadTimeoutHandler(150, TimeUnit.SECONDS))
```
**Комментарий:** LLM может ответить долго. Но не бесконечно долго — таймаут защищает от зависания.

### 8. Scope ограничение для GlobalExceptionHandler
```java
// File: exception/GlobalExceptionHandler.java, Line 25
@RestControllerAdvice(annotations = RestController.class)
```
**Комментарий:** Advice применяется ТОЛЬКО к REST-контроллерам. UI-контроллеры отдают HTML через error.html (Spring Boot BasicErrorController).

---

## 🎤 ГОТОВЫЕ ОТВЕТЫ ДЛЯ ЗАЩИТЫ

### "Почему Spring Security?"
**Ответ:** Spring Security — это production-ready фреймворк с 15-летней историей. Защищает от CSRF, XSS, SQL injection. Встроен в Spring Boot. Писать собственную систему = security risk.

### "Почему Lombok?"
**Ответ:** Lombok убирает boilerplate. 50 строк getters/setters = 1 строка @Getter @Setter. Standard в modern Java. Повышает читаемость кода.

### "Как вы справляетесь если AI недоступен?"
**Ответ:** Используем graceful fallback:
- **Extraction:** regex-паттерн ISO 6346 вместо LLM
- **Drafting:** статичный шаблон письма вместо LLM
Письмо всё равно обрабатывается, оператор просто видит меньше информации.

### "Как вы защищаетесь от двойной обработки?"
**Ответ:** Дедупликация по Message-ID:
1. На уровне БД: UNIQUE constraint
2. На уровне кода: findByMessageId перед save
Два письма с одинаковым Message-ID не могут быть в БД одновременно.

### "Что происходит при race condition?"
**Ответ:** Оптимистическая блокировка (@Version). Если два потока одновременно меняют ticket:
1. Оба читают version=5
2. Первый сохраняет: UPDATE ... WHERE version=5 ✓ (обновляет version→6)
3. Второй сохраняет: UPDATE ... WHERE version=5 ✗ (версия уже 6)
4. ObjectOptimisticLockingFailureException → retry (максимум 3 раза)

### "Почему не асинхронно?"
**Ответ:** По заданию. Синхронная обработка упрощает отладку и мониторинг. На v2 переходим на RabbitMQ для масштабирования.

### "Как вы используете PostgreSQL вместо MySQL?"
**Ответ:** PostgreSQL:
- ACID гарантии (Atomicity, Consistency, Isolation, Durability)
- UNIQUE constraints для дедупликации
- JSON типы (может пригодиться)
- Full-text search (если нужен поиск по письмам)
- Better standards compliance чем MySQL

### "Как вы мониторите?" 
**Ответ:** Micrometer + Prometheus:
- `Timer` для времени обработки
- `Counter` для количества тикетов по статусам
- Endpoint `/actuator/prometheus` возвращает метрики в формате Prometheus
- Grafana визуализирует

---

## 🚀 ДЛЯ ЗАЩИТЫ: СЦЕНАРИИ ОТВЕТОВ

### Сценарий 1: "Объясните архитектуру"

**Введение (30 сек):**
"Проект использует классическую Spring Boot архитектуру с тремя слоями:
- **Controllers** принимают HTTP-запросы
- **Services** содержат бизнес-логику
- **Repositories** работают с БД через JPA"

**Контроллеры (1 мин):**
"Есть три типа контроллеров:
- **AuthController** — REST логин
- **TicketController** — REST API для тикетов
- **TicketViewController** — Thymeleaf UI для браузера
- **AdminController** — localhost-only для администратора"

**Сервисы (1 мин):**
"Главный сервис — **TicketProcessingService**. Это оркестратор AI-конвейера:
- Шаг 1: Извлечение номера контейнера (Qwen 2.5)
- Шаг 2: Поиск контейнера в БД
- Шаг 3: Генерация черновика (Llama 3.1)
Каждый шаг имеет fallback если AI недоступен."

**Хранилище (1 мин):**
"Spring Data JPA + PostgreSQL. Используем:
- UNIQUE constraint на Message-ID для дедупликации
- @Version для защиты от race condition
- Soft delete для GDPR
- Flyway для версионирования схемы"

---

### Сценарий 2: "Какие фреймворки вы использовали?"

**Spring Security:**
- `@EnableWebSecurity` + `SecurityFilterChain`
- `BCryptPasswordEncoder` для паролей
- `DbUserDetailsService` читает юзеров из БД
- `AuthenticationManager` для программной проверки

**Spring Data JPA:**
- `JpaRepository` генерирует CRUD-методы
- `@Entity` + `@Table` маппят класс на таблицу
- `@Query` для кастомных запросов
- `@Modifying` для UPDATE/DELETE

**Spring Integration:**
- `IntegrationFlow` для конвейера данных
- `Mail.imapIdleAdapter` слушает IMAP IDLE
- `@ServiceActivator` автоматически вызывает метод
- `MessageChannel` передаёт письма между компонентами

**Spring Security + Actuator:**
- `Micrometer` собирает метрики
- `Timer`, `Counter` для сбора данных
- `/actuator/prometheus` экспортирует для Prometheus

**Flyway:**
- Версионирование SQL-миграций
- Auto-run при старте приложения
- Dokumentирование schema changes

**Lombok:**
- `@Getter`, `@Setter` генерируют accessors
- `@Builder` для паттерна Builder
- Убирает boilerplate-код

---

### Сценарий 3: "Как вы справляетесь с ошибками?"

**На уровне контроллера:**
"GlobalExceptionHandler перехватывает исключения. Важная деталь — он ограничен только @RestController классами (не применяется к @Controller). Это разделение:
- REST API получает JSON ошибки
- Браузерные пользователи видят HTML error.html

**На уровне сервиса:**
"Используем graceful fallback:
- Если Ollama timeout — используем regex для извлечения номера
- Если Ollama timeout при drafting — используем шаблон

**На уровне БД:**
"UNIQUE constraint на message_id защищает от двойного сохранения. Оптимистическая блокировка (@Version) защищает от race condition при параллельных обновлениях."

**На уровне сети:**
"WebClient с таймаутами:
- Read timeout 150 сек для Ollama (LLM медленный)
- Read timeout 10 сек для port-service (быстро)
- Connection timeout везде 5-10 сек"

---

## 📚 ГДЕ ЧТО НАХОДИТСЯ В КОДЕ

```
src/main/java/com/smartfreight/inbox/
├── config/
│   ├── SecurityConfig.java .................. Spring Security конфиг
│   ├── DbUserDetailsService.java ........... Загрузка юзеров из БД
│   ├── ImapConfig.java ..................... IMAP IDLE конфиг
│   ├── WebClientConfig.java ............... HTTP-клиенты для AI и port-service
│   └── SmtpConfig.java ..................... Отправка писем
├── controller/
│   ├── AuthController.java ................ REST /api/auth/login
│   ├── TicketController.java .............. REST /api/tickets/*
│   ├── TicketViewController.java .......... Thymeleaf /tickets/*
│   └── AdminController.java ............... Admin /admin/reprocess
├── entity/
│   ├── EmailTicket.java ................... JPA сущность письма
│   ├── User.java .......................... JPA сущность пользователя
│   ├── Container.java ..................... JPA/DTO контейнер
│   └── enums/TicketStatus.java ........... Статусы тикета
├── repository/
│   ├── EmailTicketRepository.java ......... Spring Data JPA
│   └── UserRepository.java ................ Spring Data JPA
├── service/
│   ├── AuthService.java ................... Делегирует Spring Security
│   ├── TicketProcessingService.java ....... Оркестрация AI конвейера
│   ├── AiExtractionService.java .......... Qwen 2.5 extraction
│   ├── AiDraftingService.java ............ Llama 3.1 drafting
│   ├── ContainerLookupService.java ....... Поиск в port-service
│   ├── EmailReplyService.java ............ Отправка ответа
│   └── RetryScheduler.java ............... Scheduled retry
├── integration/
│   └── EmailIngestionService.java ........ @ServiceActivator для IMAP
├── exception/
│   ├── GlobalExceptionHandler.java ....... REST error handler
│   └── ContainerNotFoundException.java ... Custom exception
├── actuator/
│   └── OllamaHealthIndicator.java ........ Health check
├── scheduler/
│   └── RetryScheduler.java ............... Retry по расписанию
└── InboxApplication.java ................. @SpringBootApplication

src/main/resources/
├── application.yaml ....................... Конфиг (IMAP, Ollama, БД)
├── db/migration/
│   ├── V1__init.sql ....................... Создание schema
│   ├── V2__seed_users.sql ................ Seed demo-пользователя
│   └── V3__*.sql .......................... Доп. миграции
└── templates/
    ├── login.html ......................... Форма входа
    ├── tickets.html ....................... Список тикетов
    ├── ticket-detail.html ................. Детали + редактирование
    └── error.html ......................... Error page
```

---

## 💡 ДЛЯ БЫСТРОГО ПОИСКА

**Где найти...**
- **Дедупликацию** → `entity/EmailTicket.java:18` + `integration/EmailIngestionService.java:77`
- **Оптимистическую блокировку** → `entity/EmailTicket.java:105-106`
- **Retry logic** → `service/TicketProcessingService.java:72-143`
- **Fallback для AI** → `service/AiExtractionService.java:102-105` + `service/AiDraftingService.java:94-97`
- **Транзакции** → `config/SecurityConfig.java` + `service/TicketProcessingService.java`
- **Prometheus метрики** → `service/TicketProcessingService.java:49-59, 125, 124`
- **IMAP IDLE** → `config/ImapConfig.java:45-102`
- **Error handling** → `exception/GlobalExceptionHandler.java`

---

*Используй этот файл как шпаргалку. Комиссия спросит — отправь их сюда! 😎*
