# Быстрый справочник по компонентам для защиты

**Используй этот файл как шпаргалку. Каждый компонент = 1 страница.**

---

## 1️⃣ SPRING SECURITY (АУТЕНТИФИКАЦИЯ)

### Где это используется?
- `SecurityConfig.java` — конфигурация
- `DbUserDetailsService.java` — загрузка юзеров
- `AuthService.java` — REST логин
- `AuthController.java` — /api/auth/login эндпоинт

### Как это работает?

```
Пользователь вводит email + пароль
    ↓
AuthController.login() получает запрос
    ↓
AuthService.login() делегирует Spring Security
    ↓
AuthenticationManager.authenticate() проверяет
    ↓
DaoAuthenticationProvider ищет пользователя
    ↓
DbUserDetailsService.loadUserByUsername() читает из БД
    ↓
PasswordEncoder.matches() сравнивает BCrypt-хеши
    ↓
✅ Пользователь аутентифицирован ИЛИ ❌ Неверные credentials
```

### Ключевые компоненты:

| Компонент | Зачем | Из коробки Spring? |
|-----------|-------|-------------------|
| `@EnableWebSecurity` | Включить все фильтры | ✅ |
| `SecurityFilterChain` | Правила доступа | ✅ |
| `BCryptPasswordEncoder` | Хеширование паролей | ✅ |
| `UserDetailsService` | Интерфейс для загрузки юзеров | ✅ |
| `AuthenticationManager` | Проверка credentials | ✅ |

### Защита:
- BCrypt медленный → защита от brute force
- Unique salt для каждого пароля → одинаковый пароль = разные хеши
- Constant-time сравнение → защита от timing attacks
- Generic error message → не рассказываем есть ли юзер в системе

### На защиту:
```
Q: "Почему BCrypt вместо MD5 или SHA?"
A: "BCrypt специально разработан для паролей:
   - Медленный алгоритм (защита от перебора)
   - Автоматическая соль
   - Constant-time сравнение
   
   MD5/SHA — для хеширования данных, не паролей.
   Это из коробки Spring Security."

Q: "Где юзеры хранятся?"
A: "В БД (таблица app_user). DbUserDetailsService их читает.
   Раньше было in-memory (хардкод), но это не production.
   Теперь — единый источник истины: БД."
```

---

## 2️⃣ SPRING DATA JPA (РАБОТА С ДАННЫМИ)

### Где это используется?
- `repository/EmailTicketRepository.java` — методы для тикетов
- `entity/EmailTicket.java` — сущность
- `entity/User.java` — сущность пользователя

### Как это работает?

```
JpaRepository интерфейс
    ↓
Spring Data генерирует SQL на основе имени метода
    ↓
Hibernate преобразует SQL в ORM операции
    ↓
Результаты маппятся обратно в Java объекты
```

### Методы которые генерируются автоматически:

```java
@Repository
public interface EmailTicketRepository extends JpaRepository<EmailTicket, Long> {
    // ✅ Это методы автоматически создаёт Spring Data:
    
    Optional<EmailTicket> findById(Long id);          // SELECT * WHERE id = ?
    List<EmailTicket> findAll();                      // SELECT *
    EmailTicket save(EmailTicket entity);             // INSERT или UPDATE
    void delete(EmailTicket entity);                  // DELETE
    
    // ✅ Это тоже генерируются по имени метода:
    Optional<EmailTicket> findByMessageId(String id); // SELECT * WHERE message_id = ?
    List<EmailTicket> findByStatus(TicketStatus st);  // SELECT * WHERE status = ?
    List<EmailTicket> findByStatusAndRetryCountLessThan(TicketStatus st, int limit);
    
    // ❌ Для сложных запросов пишем @Query:
    @Query("SELECT t.id FROM EmailTicket t WHERE t.status IN :statuses")
    List<Long> findIdsByStatusIn(@Param("statuses") Collection<TicketStatus> statuses);
}
```

### Аннотации в @Entity:

| Аннотация | Зачем | Где |
|-----------|-------|-----|
| `@Entity` | Это JPA сущность | EmailTicket.java:12 |
| `@Table(name="...")` | Имя таблицы в БД | EmailTicket.java:16 |
| `@Id` | Первичный ключ | EmailTicket.java:43 |
| `@GeneratedValue(IDENTITY)` | Auto-increment | EmailTicket.java:44 |
| `@Column(unique=true)` | UNIQUE constraint | EmailTicket.java:49 |
| `@Enumerated(STRING)` | Enum как строка | EmailTicket.java:70 |
| `@Version` | Оптимистическая блокировка | EmailTicket.java:105 |

### На защиту:

```
Q: "Зачем вы используете JPA вместо прямого SQL?"
A: "JPA дает:
   - Type-safe запросы (ошибки на compile-time, не runtime)
   - Автоматическое CRUD (50 строк = 1 interface)
   - ORM (объекты вместо raw SQL rows)
   - Кроссплатформенность (PostgreSQL, MySQL, Oracle одинаковый код)
   
   Raw SQL нужен только для очень сложных запросов.
   Spring Data JPA покрывает 90% случаев."
```

---

## 3️⃣ SPRING INTEGRATION (IMAP IDLE)

### Где это используется?
- `config/ImapConfig.java` — конфигурация flow
- `integration/EmailIngestionService.java` — обработчик

### Как это работает?

```
IMAP сервер (Gmail, Outlook...)
    ↓
ImapIdleAdapter слушает (IMAP IDLE = push, не polling)
    ↓
Письмо приходит → IMAP сервер пушит notification
    ↓
Mail.imapIdleAdapter ловит и доставляет MimeMessage
    ↓
mailChannel (MessageChannel) передаёт сообщение
    ↓
@ServiceActivator(inputChannel="mailChannel")
    ↓
EmailIngestionService.processIncomingEmail() вызывается
    ↓
Письмо обработано
```

### Компоненты:

| Компонент | Зачем |
|-----------|-------|
| `Mail.imapIdleAdapter(url)` | Слушатель писем |
| `MessageChannel` | Труба для передачи сообщений |
| `IntegrationFlow` | DSL для построения конвейера |
| `@ServiceActivator` | Обработчик входящих сообщений |

### IMAP IDLE vs Polling:

```
IMAP IDLE (что мы используем):
✅ Real-time (сервер пушит сразу)
✅ Энергоэффективно (нет polling)
✅ Меньше нагрузка на сервер

Polling (старый способ):
❌ Lag (проверяем каждые N сек)
❌ Нагрузка (мертвые запросы когда писем нет)
❌ Батарея телефона/IoT
```

### На защиту:

```
Q: "Почему IMAP IDLE вместо polling?"
A: "IMAP IDLE — это из Spring Integration. Это современный способ:
   - Сервер сам пушит notification
   - Реакция мгновенная
   - Меньше нагрузка
   
   Полинг был бы: проверяем mailbox каждые 30 сек.
   Это старый способ, неэффективный."
```

---

## 4️⃣ ОПТИМИСТИЧЕСКАЯ БЛОКИРОВКА (RACE CONDITION PROTECTION)

### Где это используется?
- `entity/EmailTicket.java` — @Version поле
- `service/TicketProcessingService.java` — retry logic

### Как это работает?

```
Сценарий: Два потока одновременно обновляют одну запись

Время | Поток 1                 | Поток 2
-----|--------------------------|---------------------------
  1  | SELECT * FROM ticket    | SELECT * FROM ticket
     | ticket.version = 5      | ticket.version = 5
     | Читают оба одинаковое   |
     |                          |
  2  | Меняют ticket.status    | Меняют ticket.status
     | Оба хотят обновить      |
     |                          |
  3  | UPDATE WHERE version=5  | UPDATE WHERE version=5
     | ✅ Успех!               | ❌ Failure! (version уже 6)
     | version → 6             | ObjectOptimisticLockingFailureException
     |                          |
  4  |                          | ↻ Retry (перечитываем, version=6)
     |                          | ✅ Второй раз успех
```

### Как Hibernate это реализует:

```java
@Entity
public class EmailTicket {
    @Version
    private Integer version;  // Hibernate сам управляет этим
}

// Первый сохранение:
// UPDATE email_ticket SET status='PROCESSING', version=6 WHERE id=1 AND version=5
// Если version не совпадает → 0 rows updated → exception

// Retry:
// Перечитываем ticket, видим version=6
// UPDATE email_ticket SET status='DRAFT_READY', version=7 WHERE id=1 AND version=6
// ✅ Успех
```

### На защиту:

```
Q: "Как вы защищаетесь от race condition?"
A: "Используем оптимистическую блокировку через @Version:
   
   EmailTicket.java имеет @Version поле.
   Hibernate автоматически увеличивает его при каждом UPDATE.
   
   Если два потока одновременно:
   1. Оба читают версию 5
   2. Первый сохраняет → версия становится 6
   3. Второй пытается UPDATE WHERE version=5 → не находит (версия уже 6)
   4. Вбрасывается ObjectOptimisticLockingFailureException
   5. Мы catch и делаем retry (максимум 3 раза)
   
   Это из коробки Hibernate. Лучше чем пессимистическая блокировка
   потому что не блокируем строку."
```

---

## 5️⃣ ДЕДУПЛИКАЦИЯ (UNIQUE CONSTRAINT)

### Где это используется?
- `entity/EmailTicket.java` — @UniqueConstraint
- `integration/EmailIngestionService.java` — проверка findByMessageId

### Как это работает?

```
Письмо приходит первый раз:
- Message-ID: <abc123@company.com>
- INSERT email_ticket(message_id='abc123@company.com', status='NEW')
- ✅ Успех

Письмо приходит второй раз (IMAP пересылает после reconnect):
- Message-ID: <abc123@company.com>
- INSERT email_ticket(message_id='abc123@company.com', status='NEW')
- ❌ БД: UNIQUE violation! (message_id уже есть)
- DataIntegrityViolationException
- Мы catch и логируем "duplicate ignored"
```

### Двухуровневая дедупликация:

```java
// Уровень 1: БД (UNIQUE constraint)
@Table(
    name = "email_ticket",
    uniqueConstraints = @UniqueConstraint(columnNames = "message_id")
)

// Уровень 2: Код (проверка перед сохранением)
if (emailTicketRepository.findByMessageId(messageId).isPresent()) {
    log.info("Duplicate email ignored: {}", messageId);
    return;  // Не сохраняем
}
```

### На защиту:

```
Q: "Как вы защищаетесь от двойной обработки одного письма?"
A: "Дедупликация по RFC 2822 Message-ID. 
   
   Двухуровневая защита:
   1. БД уровень: UNIQUE constraint на message_id
   2. Код уровень: проверка findByMessageId перед save
   
   Если IMAP пришлёт письмо дважды (после reconnect):
   - Первый раз: сохраняем
   - Второй раз: findByMessageId находит, пропускаем
   
   Даже если оба потока одновременно пройдут код-проверку,
   БД UNIQUE constraint отклонит второе INSERT.
   Это race-condition safe."
```

---

## 6️⃣ TRANSACTIONAL И PROPAGATION

### Где это используется?
- `integration/EmailIngestionService.java` — @Transactional на processIncomingEmail
- `service/TicketProcessingService.java` — @Transactional(REQUIRES_NEW)
- `controller/AdminController.java` — БЕЗ @Transactional (важно!)

### Транзакции:

```
@Transactional
public void saveTicket() {
    // BEGIN TRANSACTION
    ticket.save();        // INSERT
    // COMMIT (если без исключений)
    // или ROLLBACK (если исключение)
}
```

### Propagation:

```java
// REQUIRED (default): используй текущую транзакцию, если её нет → создай новую
@Transactional(propagation = Propagation.REQUIRED)

// REQUIRES_NEW: ВСЕГДА создай НОВУЮ транзакцию
@Transactional(propagation = Propagation.REQUIRES_NEW)

// NEVER: НЕ должно быть транзакции
@Transactional(propagation = Propagation.NEVER)
```

### Наш пример:

```java
// EmailIngestionService.processIncomingEmail()
@Transactional  // REQUIRED (default)
public void processIncomingEmail(MimeMessage msg) {
    // Сохраняем письмо в БД
    EmailTicket saved = emailTicketRepository.save(ticket);
    
    // После commit этой транзакции
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            // Запускаем обработку в НОВОЙ транзакции
            ticketProcessingService.process(saved.getId());
        }
    });
}

// TicketProcessingService.process()
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void process(Long ticketId) {
    // Каждый process() = НОВАЯ транзакция
    // Когда commit → соединение БД возвращается в пул HikariCP
}

// AdminController.reprocessAll()
public ResponseEntity<String> reprocessAll(HttpServletRequest request) {
    // БЕЗ @Transactional !
    // Это важно: если бы была @Transactional, контроллер держал бы
    // одну "жирную" транзакцию на всё время цикла (часы при тысячах тикетов)
    
    int reset = ticketProcessingService.prepareForRetry();  // Короткая TX
    List<Long> ids = emailTicketRepository.findIdsByStatusIn(...);
    ids.forEach(ticketProcessingService::process);  // Каждый = новая TX
}
```

### На защиту:

```
Q: "Зачем вы НЕ помечаете AdminController.reprocessAll() как @Transactional?"
A: "Потому что метод долгий (может час+ при тысячах тикетов и Ollama timeout 150 сек).
   
   Если бы был @Transactional:
   - Одна большая транзакция на весь процесс
   - Одно соединение БД занято весь час
   - HikariCP пул истощен (другие запросы ждут)
   - UI вешается
   
   Наш подход:
   - prepareForRetry(): короткая TX (1 UPDATE)
   - Потом цикл: каждый process() = новая TX (REQUIRES_NEW)
   - После каждого тикета соединение возвращается в пул
   - Остальной трафик не блокируется"
```

---

## 7️⃣ GRACEFUL FALLBACK (RESILIENCE)

### Где это используется?
- `service/AiExtractionService.java` — regex fallback
- `service/AiDraftingService.java` — шаблон fallback

### Как это работает?

```
AI процесс:

Шаг 1: Извлечение номера контейнера
    ↓
try:
    ✅ Ollama response = "MSCU1234567" → используй
catch Timeout/Error:
    ✅ regex fallback = ищи паттерн в тексте письма
    if found: используй
    else: return empty string (и дальше будет fallback)

Шаг 2: Поиск контейнера
    ↓
SELECT container WHERE number = 'MSCU1234567'
    ↓
if found: ✅ используй
if not found: container = null (передаём null в drafting)

Шаг 3: Генерация черновика
    ↓
try:
    ✅ Ollama response = "Спасибо за вопрос..." → используй
catch Timeout/Error:
    ✅ template fallback = заготовка письма
```

### На защиту:

```
Q: "Что происходит если Ollama недоступен?"
A: "Graceful fallback на каждом шаге:
   
   Extraction: если Ollama timeout, используем regex по ISO 6346 формату
   Drafting: если Ollama timeout, используем template письма
   
   Письмо всё равно обрабатывается:
   - Если номер контейнера найден → передаём в БД
   - Если черновик не сгенерирован → template вместо AI
   
   Результат: Оператор видит меньше информации, но не теряет работу."
```

---

## 8️⃣ PROMETHEUS МЕТРИКИ (MICROMETER)

### Где это используется?
- `service/TicketProcessingService.java` — Timer и Counter

### Как это работает?

```java
@Service
public class TicketProcessingService {
    private final Timer processingTimer;
    private final Counter draftReadyCounter;
    private final Counter failedCounter;
    
    public TicketProcessingService(MeterRegistry meterRegistry) {
        this.processingTimer = Timer.builder("smartfreight_processing_duration_seconds")
            .description("Время обработки тикета AI-конвейером")
            .register(meterRegistry);
        
        this.draftReadyCounter = Counter.builder("smartfreight_tickets_status_total")
            .tag("status", "DRAFT_READY")
            .register(meterRegistry);
    }
    
    public void process(Long ticketId) {
        long start = System.nanoTime();
        try {
            // обработка
            
            processingTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            draftReadyCounter.increment();
        } catch (Exception e) {
            failedCounter.increment();
        }
    }
}

// GET /actuator/prometheus возвращает:
// smartfreight_processing_duration_seconds_count 150
// smartfreight_processing_duration_seconds_sum 1200.5
// smartfreight_processing_duration_seconds_max 15.3
// smartfreight_tickets_status_total{status="DRAFT_READY"} 145
// smartfreight_tickets_status_total{status="FAILED"} 5
```

### На защиту:

```
Q: "Как вы мониторите производительность?"
A: "Используем Micrometer (из коробки Spring Boot Actuator):
   
   - Timer: время обработки каждого тикета
   - Counter: количество тикетов по статусам
   
   Данные экспортируем в Prometheus через /actuator/prometheus
   Потом Grafana визуализирует на графиках.
   
   Это production-standard для Java приложений."
```

---

## 9️⃣ LOMBOK (КОД-ГЕНЕРАЦИЯ)

### Где это используется?
- `entity/EmailTicket.java` — @Getter, @Setter, @Builder и т.д.

### Как это работает?

```java
@Entity
@Getter        // ← генерирует getId(), getStatus(), getBody() и т.д.
@Setter        // ← генерирует setId(), setStatus(), setBody() и т.д.
@NoArgsConstructor    // ← генерирует пустой конструктор
@AllArgsConstructor   // ← генерирует конструктор со всеми параметрами
@Builder       // ← генерирует паттерн Builder
public class EmailTicket {
    private Long id;
    private String messageId;
    // ...
}

// Использование:
EmailTicket ticket = EmailTicket.builder()
    .messageId("abc123")
    .subject("Test")
    .status(TicketStatus.NEW)
    .build();
```

### На защиту:

```
Q: "Зачем вы используете Lombok?"
A: "Lombok убирает boilerplate-код.
   
   Без Lombok:
   - 50+ строк getters/setters/constructors
   - Скучно и легко ошибиться (забыл один getter?)
   - Трудно читать суть класса
   
   С Lombok:
   - 5 аннотаций = всё сгенерируется
   - Читаемость выше
   - Standard в modern Java
   
   Это обычная практика, не экзотика."
```

---

## 🔟 FLYWAY (МИГРАЦИИ БД)

### Где это используется?
- `src/main/resources/db/migration/V*.sql` — SQL скрипты

### Как это работает?

```
Запуск приложения
    ↓
Flyway проверяет таблицу flyway_schema_history
    ↓
if (V1__init.sql НЕ в истории):
    - Выполняет V1__init.sql
    - Добавляет запись в flyway_schema_history
    
if (V2__seed_users.sql НЕ в истории):
    - Выполняет V2__seed_users.sql
    - Добавляет запись
    
Всё → БД готова к использованию
```

### Файлы в проекте:

```
src/main/resources/db/migration/
├── V1__init.sql              # Создание schema (email_ticket, app_user, container)
├── V2__seed_demo_containers.sql  # Добавление demo контейнеров
├── V3__fix_demo_user_password.sql # Обновление пароля юзера
└── V4__seed_test_tickets.sql # Добавление test данных
```

### На защиту:

```
Q: "Почему вы используете Flyway?"
A: "Flyway это версионирование БД как Git для кода:
   
   Без Flyway:
   - Как откатить schema на staging?
   - Как документировать что изменилось?
   - Как новый разработчик узнает состояние БД?
   
   С Flyway:
   - V1, V2, V3... — версии в git
   - Автоматический run при старте
   - История изменений в flyway_schema_history
   - Rollback просто: git checkout + restart
   
   Это best practice."
```

---

## ИТОГО: МАТРИЦА "ЧТО СПРОСИТЬ"

```
┌─────────────────────────┬──────────────────────────────────────────┐
│ Компонент               │ Возможный вопрос                         │
├─────────────────────────┼──────────────────────────────────────────┤
│ Spring Security         │ Почему BCrypt? Как защита от перебора?   │
│ Spring Data JPA         │ Почему JPA вместо SQL? Как CRUD?         │
│ Spring Integration      │ Почему IMAP IDLE? Как работает?          │
│ Оптимистическая блокировка│ Как защита от race condition?         │
│ Дедупликация            │ Как защита от двойной обработки?         │
│ Транзакции              │ Почему НЕ @Transactional на контроллере? │
│ Graceful Fallback       │ Что если AI недоступен?                  │
│ Prometheus              │ Как вы мониторите?                       │
│ Lombok                  │ Зачем код-генерация?                     │
│ Flyway                  │ Как версионирование БД?                  │
└─────────────────────────┴──────────────────────────────────────────┘
```

**На каждый вопрос → открыть IDE и показать конкретный код! 🎯**
