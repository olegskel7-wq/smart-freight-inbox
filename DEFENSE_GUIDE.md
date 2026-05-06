# Рекомендации к защите проекта

## Краткая история проекта

**Название**: SmartFreight Inbox — система автоматической обработки email-запросов в портовой логистике.

**MVP (v1) был завершен на базе сессии 19 апреля 2026**.

### Основной workflow

```
[Gmail IMAP] —IDLE—> inbox:8080 —> [PostgreSQL + Ollama] —> [Оператор UI] —> [Gmail SMTP]
```

1. **Входящие письма**: IMAP IDLE адаптер получает новые письма без опроса
2. **Извлечение данных**: Qwen 2.5 (LLM) извлекает номер контейнера из текста
3. **Поиск контейнера**: REST запрос к port-service для получения статуса
4. **Генерация ответа**: Llama 3.1 (LLM) пишет профессиональный черновик на английском
5. **Оператор**: Просматривает черновик в браузере, может отредактировать
6. **Отправка**: Одним нажатием отправляет ответ через SMTP обратно клиенту
7. **Мониторинг**: Prometheus + Grafana видят метрики (кол-во, время обработки, retry count)

---

## Основные точки для защиты

### 1. Архитектура (2-3 минуты)

**Диаграмма потока** (есть в README.md):

```
Email In → Ingestion → Extraction AI → Container Lookup → Drafting AI → Operator → SMTP Out
              ↓              ↓              ↓              ↓             ↓
          Validate    Qwen 2.5 + Regex   port-service   Llama 3.1    EmailReplyService
          Duplicate   Ollama:11434        :9000          Ollama        Gmail SMTP
          Message-ID
```

**Ключевые компоненты**:
- **EmailIngestionService**: Spring Integration @ServiceActivator для IMAP IDLE
- **TicketProcessingService**: Оркестратор конвейера с retry logic
- **AiExtractionService** & **AiDraftingService**: Вызовы к Ollama через WebClient
- **EmailReplyService**: SMTP отправка с RFC 2822 заголовками

---

### 2. Ключевые технологии и паттерны

#### Spring Boot 4.0.5 + Java 17

Используемые аннотации и паттерны для демонстрации:

- **@SpringBootApplication** + **@EnableScheduling** — точка входа и планировщик задач
- **@Configuration** — SmtpConfig, ImapConfig, WebClientConfig, SecurityConfig
- **@Service** — бизнес-логика сервисов
- **@RestController** + **@Controller** — REST API и Thymeleaf UI
- **@Repository** — Spring Data JPA
- **@Transactional** — управление транзакциями, propagation = REQUIRES_NEW
- **@Scheduled** — периодический RetryScheduler (каждые 2 минуты)

#### Spring Integration

```java
@Bean
public IntegrationFlow imapIdleFlow(MessageChannel mailChannel) {
    return IntegrationFlow
        .from(Mail.imapIdleAdapter(url)...
        .channel(mailChannel)
        .get();
}

@ServiceActivator(inputChannel = "mailChannel")
public void processIncomingEmail(MimeMessage mimeMessage) { ... }
```

#### Реактивное программирование (Project Reactor)

```java
WebClient ollamaWebClient
    .post()
    .uri("/api/generate")
    .bodyValue(requestBody)
    .retrieve()
    .bodyToMono(Map.class)
    .block(Duration.ofSeconds(150));  // Синхронная блокировка
```

#### Безопасность

- **BCryptPasswordEncoder** — хеширование паролей со случайной солью
- **Spring Security FilterChain** — role-based access control
- **FormLogin** — сессии в cookies (браузер)
- **Localhost-only** — /admin endpoints доступны только с 127.0.0.1
- **CSRF защита** — встроенная в Spring Security

#### Concurrency & Reliability

- **Оптимистическая блокировка** (@Version) — защита race conditions
- **Transaction isolation** — @Transactional (REQUIRES_NEW) для afterCommit callback
- **Graceful degradation** — fallback regex когда Ollama недоступна
- **Retry logic** — RetryScheduler для RETRY_LATER тикетов (MAX_RETRIES = 5)

#### Testing

- **Unit tests с Mockito** — изоляция от внешних зависимостей (Ollama, port-service)
- **Integration tests с Testcontainers** — реальная PostgreSQL в Docker
- **MockMvc** — HTTP layer testing
- **ReflectionTestUtils** — инъекция @Value в unit тестах

---

### 3. Ключевые решения и их обоснование

#### Вопрос 1: Почему используется IMAP IDLE вместо polling?

**Ответ**: 
- IMAP IDLE — асинхронный push от сервера (write to us when new email arrives)
- Polling — синхронный read каждые N секунд (чек каждый раз)
- IDLE эффективнее (меньше трафика, быстрее обнаружение), но требует постоянного соединения
- Используется Spring Integration для управления lifecycle соединения

#### Вопрос 2: Почему две разные модели LLM (Qwen для extraction, Llama для drafting)?

**Ответ**:
- Qwen 2.5 (7B) — меньше памяти, быстрее работает, хороша для точных extraction tasks (номер контейнера)
- Llama 3.1 (8B) — лучше качество текста, умнее генерирует профессиональные ответы
- На production выбирали бы based on benchmarks, но для MVP использование разных моделей показывает flexibility

#### Вопрос 3: Почему Ollama локально, а не OpenAI API?

**Ответ**:
- Ollama — открытая, локальная, без costs
- OpenAI — дорого для production, требует API ключа
- На production: выбор между Ollama (private) vs OpenAI (scalable) зависит от requirements

#### Вопрос 4: Почему нужен отдельный port-service, а не интегрировать данные в inbox?

**Ответ**:
- Разделение ответственности (microservices pattern)
- port-service может быть использован и другими системами
- Demoing: port-service имитирует реальную TOS/CMS (Terminal Operating System)
- Resilience: если port-service down — inbox всё равно работает (fallback чёрновик)

---

### 4. Статистика проекта

#### Покрытие кодом

- **35 Java файлов** всего
- **20 unit/integration тестов** со 100% проходимостью
- **14 файлов с новыми комментариями** на защиту (это документ добавил)

#### Структура

```
src/main/java/com/smartfreight/inbox/
├── config/           (4 файла) — Spring конфигурация
├── controller/       (3 файла) — REST API + Thymeleaf
├── service/          (6 файлов) — Бизнес-логика (AI + Email)
├── integration/      (1 файл)  — Spring Integration (IMAP IDLE)
├── scheduler/        (1 файл)  — @Scheduled retry logic
├── repository/       (2 файла) — Spring Data JPA
├── entity/           (5 файлов) — JPA entities + enums
├── exception/        (2 файла) — Custom exceptions + GlobalHandler
└── actuator/         (1 файл)  — Health indicator для Ollama

src/test/java/...
├── (5 service tests) — Mockito unit tests (извлечение, drafting, container lookup, email reply)
├── (1 controller test) — MockMvc REST API testing
├── (1 integration test) — @SpringBootTest с Testcontainers
└── (3 config/setup files) — Тестовая конфигурация
```

#### Зависимости (ключевые)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.integration</groupId>
    <artifactId>spring-integration-mail</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
```

---

### 5. Демонстрация на защите

#### Рекомендуемый сценарий

1. **Показать README.md** (2 мин)
   - Диаграмма архитектуры (Mermaid)
   - Endpoints
   - Как запустить через Docker Compose

2. **Запустить приложение** (3 мин)
   ```bash
   cd inbox
   docker-compose up --build
   ```
   - Показать логи: PostgreSQL, Ollama, Grafana запустились
   - Открыть браузер: http://localhost:8080/login
   - Логин: operator@smartfreight.com / demo123

3. **Отправить тестовое письмо** (2 мин)
   - Написать письмо на Gmail (содержащее номер контейнера MSCU1234567 или similar)
   - Показать как оно появляется в UI за 5-10 сек

4. **Показать черновик** (2 мин)
   - Тикет статус: DRAFT_READY (Llama сгенерировал ответ)
   - Черновик содержит реальные данные контейнера (status, ETA, location)

5. **Отправить ответ** (1 мин)
   - Нажать "Send Reply"
   - Показать что письмо ушло (в консоли log: "Reply sent for ticket X to sender@example.com")

6. **Показать Grafana дашборд** (2 мин)
   - http://localhost:3000 (admin/admin)
   - Графики: кол-во тикетов по статусам, время обработки, retry count, health Ollama

7. **Показать код** (3 мин)
   - Open EmailIngestionService.java — Spring Integration @ServiceActivator
   - Open TicketProcessingService.java — оркестрация конвейера
   - Open AiExtractionService.java — fallback regex
   - Прочитать комментарии (они на русском, показывают понимание)

8. **Ответить на вопросы** (10 мин)
   - Architecture: почему IMAP IDLE, почему две модели
   - Security: BCrypt, @Transactional REQUIRES_NEW, optimistic locking
   - Testing: Mockito, MockMvc, Testcontainers
   - Production: масштабирование, обработка ошибок, мониторинг

---

### 6. Возможные вопросы и ответы

**Q: Что случится если Ollama упадёт во время обработки?**

A: EmailIngestionService поймает исключение, сохранит тикет как FAILED. RetryScheduler каждые 2 минуты будет пытаться переобработать (до 5 раз). Fallback черновик все равно создаётся (см. AiDraftingService.buildFallbackDraft) — оператор может использовать его как базу.

**Q: Как защищено приложение от спама?**

A: На версию v1 нет Spam фильтра. На production нужно добавить:
- Проверка sender domain (SPF/DKIM)
- Rate limiting по email адресу
- ML classifier для спама

**Q: Как масштабировать систему?**

A: Сейчас mono-deployment. Для scaling:
1. Отделить Ollama в отдельный сервис (микросервис)
2. Использовать message broker (RabbitMQ/Kafka) вместо Spring Integration для IMAP
3. Горизонтальный скейлинг inbox инстансов (каждый обрабатывает тикеты из БД)
4. Кеширование контейнеров (Redis) чтобы не бить port-service

**Q: Почему используется оптимистическая блокировка, а не пессимистическая?**

A: Optimistic locking лучше для read-heavy scenarioс (много параллельных опера торов просматривают тикеты, редко пишут). Если write-heavy — переключиться на pessimistic (@Version исчезнет, добавится SELECT ... FOR UPDATE).

**Q: Почему нет JWT, только Form Login?**

A: v1 — простая реализация для MVP. REST API имеет @authenticated() но без JWT. На v2 добавить JWT:
```java
.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))
```

---

## Чек-лист перед защитой

- [ ] Прочитать все 14+ файлов с новыми комментариями
- [ ] Понять flow: Email In → Ingestion → Extraction → Lookup → Drafting → Operator → SMTP Out
- [ ] Подготовить запуск через Docker Compose
- [ ] Подготовить тестовое письмо для отправки
- [ ] Открыть README.md с диаграммой архитектуры
- [ ] Знать ответы на вопросы выше (масштабирование, security, testing)
- [ ] Показать unit tests запуск (Maven/IDE)
- [ ] Показать Grafana дашборд
- [ ] Объяснить выбор технологий (Spring Boot, IMAP IDLE, Ollama, Testcontainers)
- [ ] Продемонстрировать код на SmartIDE с комментариями

---

**Удачи на защите! Проект показывает solid understanding架构, security, testing и современные подходы Spring Boot.** ✓
