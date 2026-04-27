# Smart Freight Inbox — Полный разбор кода для защиты

**Для индустриальной аудитории (инженеры + менеджеры)**  
**Выделены места использования ready-made решений из Spring/Java экосистемы**

---

## ЧАСТЬ 1: АРХИТЕКТУРНЫЙ ОБЗОР (20–25 минут для разбора)

### 1.1 Слоёная архитектура (MVC + Service + Repository)

```
Layer 4: Controllers (REST API + Thymeleaf UI)
   ↓
Layer 3: Services (Business Logic)
   ↓
Layer 2: Repositories (Data Access)
   ↓
Layer 1: Database (PostgreSQL) + External APIs (Ollama, Port-Service)
```

**Что вы использовали:**
- ✅ **Spring MVC/WebFlux** — `@RestController`, `@Controller`, `@RequestMapping`
  - *Это из коробки Spring Boot для маршрутизации HTTP*
  - Файл: `controller/AuthController.java`, `controller/TicketController.java`

- ✅ **Spring Data JPA** — `JpaRepository`, `@Entity`, `@Transactional`
  - *Автоматическое создание SQL запросов из интерфейсов*
  - Файл: `repository/EmailTicketRepository.java`

- ✅ **Dependency Injection** — конструкторы с `@Autowired`
  - *Spring автоматически передаёт зависимости*
  - Пример: `TicketProcessingService` получает `EmailTicketRepository`, `AiExtractionService` и т.д.

---

### 1.2 Ключевые компоненты системы

#### **Компонент 1: Email Ingestion (EmailIngestionService)**

```
IMAP Server
    ↓
Spring Integration IMAP IDLE adapter
    ↓
mailChannel (MessageChannel)
    ↓
@ServiceActivator
    ↓
EmailIngestionService.processIncomingEmail()
    ↓
DATABASE: email_ticket (status=NEW)
    ↓
afterCommit() callback
    ↓
TicketProcessingService.process() (синхронно в том же потоке)
```

**Используемые технологии:**
- ✅ **Spring Integration** — `@ServiceActivator(inputChannel = "mailChannel")`
  - *Из коробки: обработчик асинхронных сообщений*
  - Файл: `integration/EmailIngestionService.java:42`

- ✅ **Lombok** — `@Getter`, `@Setter`, `@NoArgsConstructor`
  - *Автоматическое создание getters/setters (редукция boilerplate-кода)*
  - Файл: `entity/EmailTicket.java:21-31`

- ✅ **Hibernate Soft Delete** — `@SQLDelete`, `@SQLRestriction`
  - *Из коробки: логическое удаление без потери истории*
  - Файл: `entity/EmailTicket.java:35-39`

- ✅ **Unique Constraints** — `@UniqueConstraint(columnNames = "message_id")`
  - *Дедупликация писем на уровне БД: если Message-ID уже существует, БД отклонит INSERT*
  - Файл: `entity/EmailTicket.java:16-18`

---

#### **Компонент 2: AI Processing Pipeline (TicketProcessingService)**

```
TicketProcessingService.process(ticketId)
    ↓
1. AiExtractionService (Qwen 2.5 via Ollama)
   → Extract: "MSCU1234567" (container number)
    ↓
2. ContainerLookupService (HTTP to port-service)
   → Fetch: container details (location, status, ETA)
    ↓
3. AiDraftingService (Llama 3.1 via Ollama)
   → Generate: professional reply draft
    ↓
4. Save to DB: email_ticket.status = DRAFT_READY
```

**Используемые технологии:**

- ✅ **Micrometer Metrics** — `Counter`, `Timer`
  - *Из коробки Spring: сбор метрик для Prometheus*
  ```java
  this.processingTimer = Timer.builder("smartfreight_processing_duration_seconds")
      .register(meterRegistry);
  ```
  - Файл: `service/TicketProcessingService.java:49-59`

- ✅ **Optimistic Locking** — `@Version`
  - *Из Hibernate: защита от race conditions при параллельных обновлениях*
  - Файл: `entity/EmailTicket.java:68` (в полном файле)

- ✅ **Transaction Management** — `@Transactional(propagation = Propagation.REQUIRES_NEW)`
  - *Контроль границ транзакций: каждый процесс работает в своей TX*
  - Файл: `service/TicketProcessingService.java`

- ✅ **SLF4J Logging + MDC** — `Logger`, `MDC.put("ticketId", ...)`
  - *Из коробки: логирование с контекстом (для корреляции логов)*
  - Файл: `integration/EmailIngestionService.java:12`

---

#### **Компонент 3: Authentication & Security (SecurityConfig + DbUserDetailsService)**

```
POST /login (HTTP)
    ↓
AuthController.login() 
    ↓
AuthService.login(email, password)
    ↓
AuthenticationManager.authenticate()
    ↓
DbUserDetailsService.loadUserByUsername()
    ↓
SELECT * FROM app_user WHERE email = ?
    ↓
BCryptPasswordEncoder.matches(rawPassword, hashedPassword)
    ↓
✅ Success: Authentication object returned
✅ Session cookie created (httpOnly, Secure)
```

**Используемые технологии:**

- ✅ **Spring Security** — `@EnableWebSecurity`, `SecurityFilterChain`
  - *Из коробки: полная система управления доступом*
  - Файл: `config/SecurityConfig.java:34-86`

- ✅ **BCrypt Password Encoding** — `BCryptPasswordEncoder`
  - *Из коробки: безопасное хеширование паролей с солью*
  ```java
  @Bean
  public PasswordEncoder passwordEncoder() {
      return new BCryptPasswordEncoder();
  }
  ```
  - Файл: `config/SecurityConfig.java`

- ✅ **UserDetailsService Interface** — собственная реализация `DbUserDetailsService`
  - *Из Spring Security: позволяет читать юзеров из БД вместо in-memory*
  - Файл: `config/DbUserDetailsService.java` (новый файл)

- ✅ **JWT-like approach** — `AuthenticationManager` + Session
  - *Из Spring Security: аутентификация может быть session-based или token-based*
  - Файл: `service/AuthService.java`

---

#### **Компонент 4: Data Persistence (Flyway + JPA)**

```
Application Startup
    ↓
Flyway Migration Runner
    ↓
V1__init.sql (schema creation)
V2__seed_demo_containers.sql
V3__fix_demo_user_password.sql
V4__seed_test_tickets.sql
V5__drop_container_table.sql
    ↓
✅ Database ready with schema + seed data
```

**Используемые технологии:**

- ✅ **Flyway** — `spring-boot-starter-flyway`
  - *Из коробки Spring Boot: версионирование БД миграций*
  - Конфиг: `application.yaml: spring.flyway.locations=classpath:db/migration`

- ✅ **JPA Annotations** — `@Entity`, `@Table`, `@Column`, `@Id`, `@GeneratedValue`
  - *Из Hibernate/JPA: ORM маппинг Java классов → таблицы БД*
  - Файл: `entity/EmailTicket.java:12-50`

- ✅ **UNIQUE Constraints** — `uniqueConstraints`, `@UniqueConstraint`
  - *Из JPA: правила целостности данных на уровне БД*

---

## ЧАСТЬ 2: ДЕТАЛЬНЫЙ РАЗБОР ПО СЛОЯМ

### 2.1 Controller Layer (REST API + Thymeleaf)

#### **AuthController.java** — REST login endpoint

```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest request) {
        authService.login(request.email(), request.password());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
```

**Используемые технологии:**
- ✅ `@RestController` — автоматически сериализует ответы в JSON
- ✅ `@RequestBody` — Spring автоматически десериализует JSON в объект
- ✅ `ResponseEntity<T>` — контроль HTTP статуса (200, 401, 500 и т.д.)

---

#### **TicketViewController.java** — Thymeleaf UI

```java
@Controller
public class TicketViewController {
    @GetMapping("/tickets")
    public String listTickets(Model model) {
        List<EmailTicket> tickets = emailTicketRepository.findAll();
        model.addAttribute("tickets", tickets);
        return "tickets"; // Рендерит templates/tickets.html
    }
}
```

**Используемые технологии:**
- ✅ `@Controller` — рендерит HTML шаблоны (NOT JSON)
- ✅ `Model` — передаёт данные в Thymeleaf шаблон
- ✅ `Thymeleaf` — server-side HTML rendering (в `pom.xml`: `spring-boot-starter-thymeleaf`)

---

### 2.2 Service Layer (Business Logic)

#### **TicketProcessingService.java** — Оркестрация AI pipeline

```java
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class TicketProcessingService {
    // Каждый вызов process() работает в своей транзакции
    public void process(Long ticketId) {
        try {
            ticket.setStatus(TicketStatus.PROCESSING);
            
            // Шаг 1: Извлечение
            String containerNumber = aiExtractionService.extract(ticket.getBody());
            
            // Шаг 2: Поиск
            Container container = containerLookupService.findContainer(containerNumber);
            
            // Шаг 3: Генерация
            String draft = aiDraftingService.generateDraft(container, ticket.getFrom());
            
            // Шаг 4: Сохранение
            ticket.setDraftReply(draft);
            ticket.setStatus(TicketStatus.DRAFT_READY);
            emailTicketRepository.save(ticket);
        } catch (OptimisticLockingFailureException e) {
            // Retry logic
        }
    }
}
```

**Используемые технологии:**
- ✅ `@Service` — Spring компонент для бизнес-логики
- ✅ `@Transactional` — автоматическое управление транзакциями (начало, commit, rollback)
- ✅ `Propagation.REQUIRES_NEW` — каждый вызов = новая транзакция
- ✅ `Micrometer Timer` — сбор метрик обработки

---

#### **ContainerLookupService.java** — HTTP к port-service

```java
@Service
public class ContainerLookupService {
    private final WebClient webClient;
    
    public Container findContainer(String number) {
        return webClient.get()
            .uri("/api/containers/{number}", number)
            .retrieve()
            .bodyToMono(ContainerResponse.class)
            .timeout(Duration.ofSeconds(5))
            .block(); // синхронное ожидание
    }
}
```

**Используемые технологии:**
- ✅ `WebClient` — из Spring WebFlux (неблокирующий HTTP клиент)
- ✅ `Mono<T>` — reaktivní тип для одного результата
- ✅ `.block()` — синхронное ожидание асинхронного результата

---

### 2.3 Repository Layer (Data Access)

#### **EmailTicketRepository.java** — Spring Data JPA

```java
@Repository
public interface EmailTicketRepository extends JpaRepository<EmailTicket, Long> {
    // Spring автоматически реализует эти методы
    Optional<EmailTicket> findByMessageId(String messageId);
    List<EmailTicket> findByStatus(TicketStatus status);
    
    // Кастомный запрос
    @Query("SELECT t.id FROM EmailTicket t WHERE t.status IN :statuses ORDER BY t.id")
    List<Long> findIdsByStatusIn(@Param("statuses") Collection<TicketStatus> statuses);
    
    // Bulk-update
    @Modifying
    @Query("UPDATE EmailTicket t SET t.status = ?1 WHERE t.status = ?2")
    int updateStatus(TicketStatus newStatus, TicketStatus oldStatus);
}
```

**Используемые технологии:**
- ✅ `JpaRepository<T, ID>` — предоставляет CRUD операции автоматически
- ✅ `@Query` — кастомные JPQL запросы (вместо SQL)
- ✅ `@Modifying` — для UPDATE/DELETE операций
- ✅ Spring Data Query Methods — `findByXxx`, `findByXxxAndYyy` — генерируются автоматически

---

### 2.4 Entity Layer (Domain Models)

#### **EmailTicket.java** — JPA Entity с Lombok

```java
@Entity
@Table(name = "email_ticket", uniqueConstraints = @UniqueConstraint(columnNames = "message_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@SQLDelete(sql = "UPDATE email_ticket SET deleted = true WHERE id=?")
@SQLRestriction("deleted=false")
public class EmailTicket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "message_id", nullable = false, unique = true)
    private String messageId; // RFC 2822 Message-ID для дедупликации
    
    @Enumerated(EnumType.STRING)
    private TicketStatus status; // NEW, PROCESSING, DRAFT_READY, FAILED, RETRY_LATER, CLOSED
    
    @Version
    private Integer version; // Оптимистическая блокировка
    
    private LocalDateTime createdAt; // Автоматически устанавливается @CreationTimestamp
}
```

**Используемые технологии:**
- ✅ `@Entity` — JPA маппинг на таблицу БД
- ✅ `@GeneratedValue(IDENTITY)` — автоинкрементный ID
- ✅ `@Enumerated(STRING)` — сохранение enum как строки в БД
- ✅ `@Version` — Hibernate оптимистическая блокировка (race condition protection)
- ✅ `@SQLDelete` + `@SQLRestriction` — GDPR мягкое удаление
- ✅ `Lombok` аннотации — автогенерация getters/setters/constructors/builder

---

## ЧАСТЬ 3: ИНТЕГРАЦИИ И ВНЕШНИЕ СИСТЕМЫ

### 3.1 Ollama Integration (AI Models)

**Qwen 2.5 (Extraction):**
```java
@Service
public class AiExtractionService {
    private final WebClient ollama;
    
    public String extract(String emailBody) {
        // HTTP POST к localhost:11434/api/generate
        // Model: qwen:latest
        // Prompt: "Extract container number from: {emailBody}"
        // Response: {"response": "MSCU1234567"}
    }
}
```

**Используемые технологии:**
- ✅ `WebClient` для HTTP POST к Ollama
- ✅ Timeout: 10 сек (настроен в `WebClientConfig`)
- ✅ Regex fallback если Ollama timeout

---

### 3.2 Spring Integration (IMAP IDLE)

```java
@Configuration
public class ImapConfig {
    @Bean
    public IntegrationFlow imapFlow() {
        return IntegrationFlows
            .from(Mail.imapIdleAdapter(...))
            .channel("mailChannel")
            .get();
    }
}
```

**Используемые технологии:**
- ✅ `Spring Integration` — асинхронная обработка сообщений
- ✅ `ImapIdleAdapter` — слушает IMAP IDLE без polling
- ✅ `@ServiceActivator` — автоматический вызов метода при получении письма

---

### 3.3 Prometheus Metrics

```java
// TicketProcessingService конструктор
this.processingTimer = Timer.builder("smartfreight_processing_duration_seconds")
    .description("Время обработки тикета AI-конвейером")
    .register(meterRegistry);

this.draftReadyCounter = Counter.builder("smartfreight_tickets_status_total")
    .tag("status", "DRAFT_READY")
    .description("Количество тикетов по статусу")
    .register(meterRegistry);

// Использование в коде
processingTimer.recordCallable(() -> {
    // выполнить обработку
});

draftReadyCounter.increment();
```

**Используемые технологии:**
- ✅ `Micrometer` — из коробки Spring Boot Actuator
- ✅ `Counter`, `Timer` — метрики для Prometheus
- ✅ Endpoint: `GET /actuator/prometheus` — экспортирует метрики

---

## ЧАСТЬ 4: МЕСТА ДЛЯ ОТВЕТОВ "ЭТО ИЗ КОРОБКИ SPRING"

### 🎯 **Когда комиссия спросит: "Почему вы использовали X вместо Y?"**

---

#### **❓ "Почему Spring Security вместо собственной системы доступа?"**

**✅ Ответ:**
"Spring Security — это production-ready фреймворк с 15-летней историей. Он:
- Автоматически защищает от CSRF, XSS, SQL injection
- Интегрирован со всеми Spring компонентами
- Имеет встроенные фильтры для логирования попыток входа
- Поддерживает multiple аутентификации (session, JWT, OAuth2)

Писать собственную систему — это security risk (легко ошибиться с хешированием, солью, session management). Spring Security — это best practice в Java экосистеме."

**Файлы:**
- `config/SecurityConfig.java` — конфигурация
- `config/DbUserDetailsService.java` — интеграция с БД

---

#### **❓ "Зачем вам Lombok, если можно написать getters/setters вручную?"**

**✅ Ответ:**
"Lombok убирает boilerplate-код. Вместо 50 строк getters/setters/constructors писаи одну строку `@Getter @Setter @NoArgsConstructor`. Это:
- Повышает читаемость (видно суть класса, а не getter-ы)
- Снижает вероятность ошибок (забыли написать один getter?)
- Стандарт в modern Java (используется в Spring Boot examples)

В production это используют везде потому что это экономит время разработки."

**Файлы:**
- `entity/EmailTicket.java` — @Getter, @Setter, @NoArgsConstructor, @AllArgsConstructor, @Builder

---

#### **❓ "Почему вы использовали Flyway для миграций БД?"**

**✅ Ответ:**
"Flyway — это версионирование БД как версионирование кода в Git. Без него:
- Как вы откатываете схему на staging?
- Как вы документируете изменения схемы?
- Как новый разработчик узнает в каком состоянии БД?

Flyway автоматически:
- Отслеживает какие миграции уже прошли (таблица flyway_schema_history)
- Запускает новые миграции при старте приложения
- Откатывается если миграция сломалась

Это best practice в профессиональных проектах."

**Файлы:**
- `src/main/resources/db/migration/V1__init.sql` и т.д.

---

#### **❓ "Как вы управляете конфликтами при параллельных обновлениях?"**

**✅ Ответ:**
"Используем оптимистическую блокировку через `@Version` аннотацию в Hibernate. Каждый тикет имеет поле `version`:

1. Читаем ticket, version=5
2. Меняем status=DRAFT_READY
3. Пытаемся обновить: UPDATE email_ticket SET status=?, version=6 WHERE id=? AND version=5
4. Если другой thread уже изменил version, UPDATE не выполнится (0 rows), и мы получим OptimisticLockingFailureException
5. Catch и retry (3 попытки максимум)

Это из коробки Hibernate. Лучше, чем пессимистическая блокировка потому что:
- Не блокируем строку (не блокируем других читателей)
- Быстро при редких конфликтах
- Конфликты автоматически решаются через retry"

**Файлы:**
- `entity/EmailTicket.java:68` — `@Version private Integer version`
- `service/TicketProcessingService.java` — retry logic

---

#### **❓ "Почему вы выбрали PostgreSQL вместо MySQL/MongoDB?"**

**✅ Ответ:**
"PostgreSQL — это enterprise database с:
- ACID гарантиями (Atomicity, Consistency, Isolation, Durability)
- Поддержкой UNIQUE constraints (дедупликация по Message-ID)
- JSON типами (если нужно)
- Full-text search (для поиска по письмам)
- Лучше чем MySQL в terms of standards compliance

MongoDB — это NoSQL, нужен когда:
- Нет schema (гибкость важнее)
- Очень большие volumes (горизонтальное масштабирование)
- Нам нужна schema (email_ticket с фиксированными полями), ACID и strong consistency. PostgreSQL идеален."

---

#### **❓ "Как вы интегрировались с IMAP?"**

**✅ Ответ:**
"Использовал Spring Integration с IMAP IDLE адаптером. Это из коробки Spring Framework:

```java
Mail.imapIdleAdapter(store, ...settings...)
```

IMAP IDLE vs. polling:
- IMAP IDLE: сервер пушит уведомления сразу (real-time)
- Polling: каждые N сек проверяем mailbox

IDLE энергоэффективнее и быстрее. Spring Integration handles:
- Reconnection logic при разрыве соединения
- Message serialization/deserialization
- Exception handling

Писать это самостоятельно — ошибка, потому что очень сложно правильно handle reconnects."

**Файлы:**
- `config/ImapConfig.java` — конфигурация IMAP IDLE
- `integration/EmailIngestionService.java` — обработчик

---

#### **❓ "Почему вы выбрали Spring Data JPA вместо прямого SQL?"**

**✅ Ответ:**
"Spring Data JPA — это ORM (Object-Relational Mapping). Плюсы:
- Type-safe queries (compile-time проверка vs. SQL string errors at runtime)
- Автоматическое создание CRUD методов из интерфейса
- Кроссплатформенность (одинаков код для PostgreSQL, MySQL, Oracle)
- Встроенная поддержка pagination, sorting
- Интеграция с @Transactional для управления транзакциями

JPA — это Java standard (часть Jakarta EE), поддерживается везде.

Raw SQL иногда нужен для сложных запросов, но 80% операций — это CRUD, и JPA решает их за пару аннотаций."

**Файлы:**
- `repository/EmailTicketRepository.java` — Spring Data JPA

---

#### **❓ "Как вы мониторите производительность?"**

**✅ Ответ:**
"Используем Micrometer (из коробки Spring Boot Actuator) и Prometheus:

```java
Timer processingTimer = Timer.builder("smartfreight_processing_duration_seconds")
    .register(meterRegistry);

processingTimer.record(() -> {
    // обработка тикета
});
```

Эндпоинт `/actuator/prometheus` экспортирует метрики в формате Prometheus. Потом:
- Prometheus скрапит эти метрики (сохраняет в TSDB)
- Grafana визуализирует (красивые графики)

Это стандарт для мониторинга Java приложений. Больше, чем System.out.println()!"

**Файлы:**
- `service/TicketProcessingService.java` — использование Timer, Counter
- `config/WebClientConfig.java` — таймауты для Ollama/port-service

---

## ЧАСТЬ 5: ВОПРОСЫ, КОТОРЫЕ КОМИССИЯ МОЖЕТ ЗАДАТЬ

### Вопрос 1: "Как ваша система обрабатывает ошибки?"

**Готовый ответ:**
"У нас три уровня обработки:

1. **На уровне контроллера:** `GlobalExceptionHandler` перехватывает исключения из REST API
2. **На уровне сервиса:** Fallback-и для Ollama (regex extraction, template drafting)
3. **На уровне БД:** Unique constraint на message_id (дедупликация автоматическая)

Если Ollama timeout:
```java
try {
    return ollama.call(..., timeout=10sec);
} catch (TimeoutException) {
    return regexFallback.extract(...);
}
```

Если race condition при обновлении:
```java
catch (OptimisticLockingFailureException) {
    retry(); // максимум 3 попытки
}
```

Ни при какой ошибке мы не потеряем данные (email_ticket в БД остаётся)."

---

### Вопрос 2: "Как масштабируется ваша система?"

**Готовый ответ:**
"v1 синхронное и single-instance (1 процесс). Bottleneck = Ollama latency (8-10 сек на тикет).

v2 roadmap:
- RabbitMQ queue — запуск N worker процессов параллельно
- Redis кэш — для часто запрашиваемых контейнеров
- Horizontal scaling — запустить 10 инстансов = 10x throughput

На v1 пропускная способность ~50-100 писем/день (на 1 GPU).
На v2 с 2 GPUs и RabbitMQ — ~1000 писем/день."

---

### Вопрос 3: "Как вы гарантируете, что письмо не обработаетсядважды?"

**Готовый ответ:**
"Дедупликация по Message-ID (RFC 2822 стандарт).

На уровне БД: UNIQUE constraint
```sql
UNIQUE(message_id)
```

Если IMAP пересылает одно письмо дважды после reconnect:
1. Первый раз: INSERT email_ticket(message_id='abc@host')
2. Второй раз: INSERT email_ticket(message_id='abc@host') → БД отклоняет (unique violation)

Я catch DataIntegrityViolationException и log warning, но письмо не создастся. Safe."

---

## ЧАСТЬ 6: SUMMARY ДЛЯ ЗАЩИТЫ (2-3 минуты для озвучивания)

### Архитектура в 30 секунд

"Проект использует классическую Spring Boot архитектуру:
- **Controllers** принимают HTTP запросы
- **Services** (TicketProcessingService, AiExtractionService) — бизнес логика
- **Repositories** (Spring Data JPA) — доступ к БД
- **Entities** (EmailTicket) — domain model

Данные живут в PostgreSQL. Мониторинг через Prometheus + Grafana.

Все компоненты из Spring Boot экосистемы — это production-ready, battle-tested решения."

### Ключевые навыки которые вы продемонстрировали

1. ✅ **Spring Boot architecture** — правильное разделение на слои
2. ✅ **Spring Security** — proper authentication & authorization
3. ✅ **Spring Data JPA** — ORM, repositories, queries
4. ✅ **Transactions & Locking** — `@Transactional`, `@Version` для race conditions
5. ✅ **Monitoring** — Micrometer metrics, Prometheus integration
6. ✅ **API Design** — RESTful endpoints, proper HTTP status codes
7. ✅ **Database Design** — UNIQUE constraints, migrations (Flyway), soft delete
8. ✅ **Error Handling** — graceful fallbacks, retry logic
9. ✅ **Integration** — IMAP IDLE, HTTP clients (WebClient), Ollama API
10. ✅ **Code Quality** — Lombok для чистоты, логирование с MDC

---

## БЫСТРЫЙ СПРАВОЧНИК ДЛЯ ЗАЩИТЫ

| Компонент | Из коробки Spring | Файл | Зачем |
|-----------|-------------------|------|-------|
| Controllers | `@RestController`, `@RequestMapping` | `controller/*` | Маршрутизация HTTP |
| Services | `@Service` | `service/*` | Бизнес логика |
| Repositories | `JpaRepository` | `repository/*` | CRUD операции |
| Security | `@EnableWebSecurity`, `SecurityFilterChain` | `config/SecurityConfig.java` | Аутентификация |
| Monitoring | `Micrometer`, `@Timed`, `Counter` | `service/TicketProcessingService.java` | Prometheus метрики |
| Migrations | `Flyway` | `db/migration/*` | Версионирование БД |
| ORM | `JPA`, `@Entity`, `@Version` | `entity/EmailTicket.java` | Объектно-реляционное маппинг |
| Integration | `@ServiceActivator`, `IntegrationFlow` | `config/ImapConfig.java` | IMAP IDLE |
| Logging | `SLF4J`, `MDC` | везде | Структурированные логи |
| Code generation | `Lombok` | везде | Убирает boilerplate |

---

*Конец разбора кода. Готовый материал для защиты!*
