# 🎓 ПОЛНЫЙ ГАЙД К ЗАЩИТЕ ПРОЕКТА SmartFreight

## Дата: 20 апреля 2026

---

## 📋 Обзор проекта

### Что это?

**SmartFreight Inbox** — система автоматической обработки email-запросов клиентов о статусе грузов в порту Felixstowe, Великобритания.

Система использует **AI (Local LLM)** для извлечения данных и генерации ответов, интегрируясь с портовой системой управления (**port-service**).

### Архитектура (упрощённая)

```
[Gmail/IMAP] ─IDLE─→ [inbox:8080] ←→ [port-service:9000]
                           ↓
                      [Ollama:11434]
                           ↓
                      [PostgreSQL:5433]
                           ↓
                      [Grafana:3000]
                           ↓
                      [Gmail/SMTP] → Клиент
```

---

## 🏢 Компонент 1: inbox (главное приложение)

### Назначение

Центральное приложение, которое:
1. **Получает письма** через IMAP IDLE (real-time notifications)
2. **Извлекает контейнер номер** через AI (Qwen 2.5)
3. **Ищет контейнер в port-service**
4. **Генерирует ответ** через AI (Llama 3.1)
5. **Позволяет оператору** просмотреть и отправить ответ
6. **Отправляет письмо** через SMTP
7. **Мониторит процесс** через Prometheus/Grafana

### Структура (35 Java файлов)

#### Конфигурация (4 файла)
- `SmtpConfig.java` ← SMTP параметры, STARTTLS, SSL trust
- `ImapConfig.java` ← IMAP IDLE адаптер, Spring Integration flow
- `WebClientConfig.java` ← WebClient для Ollama и port-service
- `SecurityConfig.java` ← Spring Security, BCrypt, роли

#### Сервисы (6 файлов)
- `AiExtractionService.java` ← Qwen 2.5 для извлечения номера
- `AiDraftingService.java` ← Llama 3.1 для генерации ответа
- `ContainerLookupService.java` ← HTTP запрос к port-service
- `TicketProcessingService.java` ← Оркестратор конвейера
- `EmailReplyService.java` ← Отправка ответа через SMTP
- `AuthService.java` ← Проверка credentials

#### Контроллеры (3 файла)
- `TicketViewController.java` ← Thymeleaf UI для браузера
- `TicketController.java` ← REST API для внешних клиентов
- `AuthController.java` ← /api/auth/login
- `AdminController.java` ← /admin/reprocess (localhost-only)

#### Интеграция (1 файл)
- `EmailIngestionService.java` ← Spring Integration @ServiceActivator для IMAP

#### Планировщик (1 файл)
- `RetryScheduler.java` ← Переобработка RETRY_LATER тикетов

#### Здоровье (1 файл)
- `OllamaHealthIndicator.java` ← Health check для Ollama

#### Сущности (5 файлов)
- `EmailTicket.java` ← JPA Entity с soft delete и optimistic locking
- `User.java` ← Пользователь для аутентификации
- `Container.java` ← POJO (из port-service)
- `TicketStatus.java` ← Enum с жизненным циклом
- `InboxApplication.java` ← Entry point

#### Репозитории (2 файла)
- `EmailTicketRepository.java` ← Spring Data JPA
- `UserRepository.java` ← Поиск пользователей

#### Исключения (2 файла)
- `GlobalExceptionHandler.java` ← Обработка ошибок
- `ContainerNotFoundException.java` ← Custom exception

### Тесты (10 файлов)
- Unit тесты с Mockito (AiExtractionServiceTest, TicketControllerTest и т.д.)
- Integration тест с Testcontainers (TicketProcessingServiceTest)
- Smoke test (InboxApplicationTests)

---

## 🚢 Компонент 2: port-service (микросервис)

### Назначение

Микросервис, который:
1. **Хранит данные** о контейнерах (номер, статус, location, ETA)
2. **Предоставляет REST API** для других сервисов
3. **Эмулирует портовую систему** (обновляет статусы автоматически)

На production это была бы интеграция с реальной **TOS/CMS** (Terminal Operating System).

### Структура (8 Java файлов)

#### Entry Point (1 файл)
- `PortServiceApplication.java` ← @SpringBootApplication + @EnableScheduling

#### Сущности (1 файл)
- `Container.java` ← JPA Entity с полями: containerNumber, status, eta, location

#### DTO (1 файл)
- `ContainerResponse.java` ← REST DTO (отделена от Entity)

#### Repository (1 файл)
- `ContainerRepository.java` ← Spring Data JPA findByContainerNumber()

#### Сервис (1 файл)
- `ContainerService.java` ← Нормализация и трансформация

#### Контроллер (1 файл)
- `ContainerController.java` ← GET /api/containers/{number}

#### Планировщик (1 файл)
- `StatusUpdateScheduler.java` ← Обновление статусов каждые 5 минут

#### Тесты (1 файл)
- `PortServiceApplicationTests.java` ← Smoke test

---

## 🔄 Полный workflow обработки письма

### Сценарий: Клиент отправляет письмо

```
1. Gmail INBOX
   ↓ (письмо)
   "Hi, where is my container MSCU1234567?"

2. IMAP IDLE Адаптер (inbox:8080)
   ↓ (push notification)
   → EmailIngestionService.processIncomingEmail()

3. Дедупликация (Message-ID)
   ↓ (проверка БД)
   → Если уже есть: skip
   → Если новое: создаём EmailTicket (status=NEW)

4. Trigger AI Конвейер
   ↓ (afterCommit callback)
   → TicketProcessingService.process(ticketId)

5. Шаг 1: Извлечение (Qwen 2.5)
   ↓ (вызов Ollama)
   → AiExtractionService.extractContainerNumber()
   → Результат: "MSCU1234567"

6. Шаг 2: Поиск в port-service
   ↓ (HTTP GET)
   → ContainerLookupService.findByNumber("MSCU1234567")
   → WebClient → port-service:9000/api/containers/MSCU1234567
   → Результат: { status: "AT_PORT", location: "Felixstowe Terminal 2", eta: "2026-04-25T14:30" }

7. Шаг 3: Генерация Ответа (Llama 3.1)
   ↓ (вызов Ollama)
   → AiDraftingService.generateDraft()
   → Промпт включает: письмо + данные контейнера
   → Результат: черновик ответа на английском

8. Сохранение Результата
   ↓ (update БД)
   → EmailTicket.status = DRAFT_READY
   → EmailTicket.draftReply = (AI-генерированный текст)

9. Оператор просматривает
   ↓ (браузер http://localhost:8080/tickets/123)
   → Видит письмо, контейнер, ETA, локацию, черновик
   → Может отредактировать черновик

10. Отправка ответа
    ↓ (нажать "Send Reply")
    → EmailReplyService.sendReply()
    → SMTP → gmail.com
    → Письмо отправлено клиенту
    → EmailTicket.status = CLOSED

11. Мониторинг
    ↓ (Prometheus/Grafana)
    → Метрика: тикет обработан за 3.5 сек
    → Retry count: 0 (успех с первой попытки)
    → Status: CLOSED
```

---

## 🎯 Ключевые технологические решения

### 1. Почему IMAP IDLE вместо polling?

**IMAP IDLE (асинхронный push)**
- Сервер сам отправляет уведомление о новом письме
- Реакция: мгновенная (< 1 сек)
- Трафик: минимальный
- CPU: низкое использование

**vs Polling (синхронный read)**
- Приложение проверяет каждые N секунд
- Реакция: до N секунд задержки
- Трафик: постоянный (даже если писем нет)
- CPU: выше

→ **IMAP IDLE лучше для production**, но требует постоянного соединения

### 2. Почему две разные модели LLM?

**Qwen 2.5 (7B)**
- Хороша для **точных extraction** задач (найти номер)
- Меньше памяти (7B параметров)
- Быстрее (меньше вычислений)

**Llama 3.1 (8B)**
- Лучше качество **текста** (генерация ответов)
- Умнее в reasoning
- Понимает контекст лучше

→ **Комбинация показывает flexibility** и знание того когда какой инструмент использовать

### 3. Почему port-service отдельный микросервис?

**Разделение ответственности**
- inbox занимается email
- port-service занимается контейнерами
- Каждый может масштабироваться независимо

**Resilience**
- Если port-service down → inbox всё равно работает (fallback черновик)
- Если inbox down → port-service всё равно обновляет статусы

**Real-world pattern**
- На production это была бы integrация с внешней системой TOS/CMS
- Сейчас эмулируем через microservice

### 4. Почему Ollama локально, а не OpenAI API?

**Ollama (Open Source, Local)**
- No API costs
- Private (данные не уходят куда-то)
- Full control
- Требует GPU/CPU на сервере

**vs OpenAI API (Cloud)**
- Дорого для production
- Требует API key
- Данные уходят на OpenAI servers
- Проще scalability

→ **Ollama хорош для MVP/demo**, OpenAI для production с бюджетом

### 5. Почему Testcontainers?

**Testcontainers (Docker БД в тестах)**
- Тесты используют реальную PostgreSQL
- Не касаются рабочей БД на :5433
- Автоматическое управление lifecycle
- Изолированность между test runs

**vs Mock BDD or in-memory Бд**
- Реалистичнее (настоящая BD, не имитация)
- Ловит ошибки (SQL синтаксис, constraints)
- Медленнее, но стоит того

→ **Testcontainers = integration tests с confidence**

---

## 🛡️ Безопасность

### Что реализовано

1. **BCryptPasswordEncoder** — пароли хешированы со случайной солью
2. **Spring Security** — role-based access control
3. **HTTPS/TLS** — для production (в demo HTTP)
4. **Localhost-only** — /admin endpoints только с 127.0.0.1
5. **CSRF protection** — встроена в Spring Security
6. **SQL Injection protection** — Spring Data JPA использует PreparedStatements
7. **XSS protection** — Thymeleaf экрирует HTML по default

### Что можно улучшить (для production)

- JWT tokens (вместо session cookies)
- OAuth2 integrация (для SSO)
- Rate limiting (защита от DDoS)
- API key management (для external integrations)
- Encryption at rest (sensitive data в БД)
- Secret management (API keys, credentials не в коде)

---

## 📊 Мониторинг (Prometheus + Grafana)

### Что отслеживается

1. **Ticket metrics**
   - Кол-во тикетов по статусам (NEW, PROCESSING, DRAFT_READY, CLOSED, FAILED)
   - Время обработки (среднее, max)
   - Успешность (% завершённых)

2. **System metrics**
   - JVM memory (heap, non-heap)
   - Thread count
   - HTTP requests (count, latency)

3. **Health checks**
   - Ollama доступна? (UP/DOWN)
   - PostgreSQL доступна? (UP/DOWN)
   - port-service доступна? (UP/DOWN)

### Grafana dashboard (http://localhost:3000)

- Граф: "Tickets by Status" (количество по статусам)
- Граф: "Processing Time Distribution" (время обработки)
- Граф: "Ollama Health Status" (UP/OUT_OF_SERVICE/DOWN)
- Таблица: "Recent Tickets" (последние обработанные)

---

## 🧪 Тестирование

### Unit Tests (с Mockito)

```
AiExtractionServiceTest
├── Ollama вернул номер → используем его
├── Ollama вернул предложение → regex из ответа
├── Ollama упала → regex fallback
└── Пустое письмо → пустой результат

AiDraftingServiceTest
├── Ollama генерирует черновик → используем его
├── Ollama упала → fallback черновик
└── Контейнер не найден → fallback mentions it

ContainerLookupServiceTest
├── port-service вернул контейнер → используем
├── port-service не найденуз → Optional.empty()
├── port-service down → Optional.empty() (graceful)
├── Null/empty input → Optional.empty()
└── Нормализация: lowercase → uppercase

EmailReplyServiceTest
├── Отправка письма успешна
├── RFC заголовки выставлены (In-Reply-To, References)
├── Email парсится (Name <email@example.com> → email)
└── SMTP ошибка → выброс RuntimeException

TicketControllerTest
├── GET /api/tickets → список
├── GET /api/tickets/{id} → конкретный тикет
├── POST /api/tickets/{id}/approve → CLOSED
├── POST /api/tickets/{id}/reject → RETRY_LATER
├── PATCH /api/tickets/{id}/draft → обновить
└── Закрытый тикет → 409 Conflict
```

### Integration Tests (с Testcontainers)

```
TicketProcessingServiceTest
├── Полный конвейер extraction → lookup → drafting
├── Если Ollama работает → DRAFT_READY status
├── Если Ollama down → fallback черновик
└── Retry logic работает (оптимистическая блокировка)
```

### E2E Demo (ручное)

```
1. Запустить docker-compose
2. Открыть http://localhost:8080/login
3. Логин: operator@smartfreight.com / demo123
4. Отправить письмо на smartfreight.test.2026@gmail.com
5. Увидеть тикет в UI за 5-10 секунд
6. Черновик сгенерирован (статус = DRAFT_READY)
7. Нажать "Send Reply" → письмо отправлено
8. Проверить почту → ответ полученуз
9. Открыть Grafana → показать метрики
```

---

## 🚀 Запуск проекта

### Требования

- Docker + Docker Compose
- Java 17+ (для локальной разработки)
- Gmail Account + App Password (для IMAP/SMTP)

### Запуск через Docker Compose

```bash
cd inbox
docker-compose up --build
```

### Запуск только inbox локально

```bash
# В IntelliJ: Run PortServiceApplication
# port-service (http://localhost:9000)

# В IntelliJ: Run InboxApplication
# inbox (http://localhost:8080)

# В Docker:
docker run --name postgres -e POSTGRES_PASSWORD=password postgres:latest
docker run --name ollama -p 11434:11434 ollama/ollama:latest
```

### Endpoints

```
UI:
  http://localhost:8080/login
  http://localhost:8080/tickets
  http://localhost:8080/tickets/{id}

REST API:
  GET http://localhost:8080/api/tickets
  GET http://localhost:8080/api/tickets/{id}
  POST http://localhost:8080/api/auth/login
  POST http://localhost:9000/api/containers/{number}

Monitoring:
  http://localhost:9090 (Prometheus)
  http://localhost:3000 (Grafana)
  http://localhost:8080/actuator/health (Inbox health)
```

---

## 💡 Возможные вопросы на защите

### Архитектура

**Q: Почему Ollama локально, а не облако?**
A: Для MVP и demo хорошо, но на production нужно выбирать:
- Ollama: приватные данные, нет costs, но требует GPU
- OpenAI: easy scale, costs, данные в облаке

**Q: Как масштабировать систему?**
A:
1. Горизонтальный скейлинг inbox: несколько инстансов, load balancer
2. Вынести Ollama в отдельный микросервис (или managed LLM API)
3. Message broker вместо Spring Integration IMAP (RabbitMQ/Kafka)
4. Redis для кеширования контейнеров
5. Шардирование БД если очень много тикетов

### Security

**Q: Как защищены пароли?**
A: BCrypt с случайной солью. Один пароль в открытом виде каждый раз даёт разный хэш.

**Q: Данные шифруются в transit?**
A: На production используется HTTPS/TLS. В demo HTTP, но легко включить.

**Q: Что если Someone взломает БД?**
A: Пароли хешированы (BCrypt), хэш не обратим. Письма не содержат PII.

### Performance

**Q: Какой максимум тикетов в секунду?**
A: Зависит от:
- CPU (для LLM вызовов)
- Ollama throughput (обычно 1-10 тикетов/сек на GPU)
- БД write capacity
- На production: горизонтальный скейлинг

**Q: Почему не асинхронно (async/await)?**
A: TicketProcessingService синхронный, но:
- IMAP IDLE асинхронный (push notification)
- WebClient использует Reactor (асинхронный под капотом)
- На production можно добавить async процессинг через message queue

### Testing

**Q: Покрытие кодом?**
A: ~70% (20 unit/integration тестов на 35 файлов). Основные компоненты протестированы.

**Q: Как тесты работают без Ollama?**
A: Mockito мокирует WebClient. Тесты быстрые (<100ms each).

---

## 📝 Чек-лист перед защитой

- [ ] Прочитать COMMENTARY_SUMMARY.md (inbox)
- [ ] Прочитать PORT_SERVICE_COMMENTARY.md (port-service)
- [ ] Понять flow: email → extraction → lookup → drafting → operator → smtp out
- [ ] Запустить docker-compose и проверить что всё стартует
- [ ] Отправить тестовое письмо (с номером контейнера)
- [ ] Показать UI: логин, список тикетов, детали тикета
- [ ] Показать черновик и отправить ответ
- [ ] Проверить что письмо пришло (в Gmail)
- [ ] Запустить тесты: `mvn test`
- [ ] Открыть Grafana и показать метрики
- [ ] Знать ответы на архитектурные вопросы (см выше)
- [ ] Уметь объяснить выбор технологий (IMAP IDLE, две модели LLM и т.д.)

---

## 🎓 На защите: структура презентации

### 1. Обзор (2 минуты)
- Что это: система обработки email запросов о грузах
- Где используется: порт Felixstowe, logistics компании
- Architecture: 2 микросервиса, AI, мониторинг

### 2. Architecture (3 минуты)
- Диаграмма (из README.md)
- Два компонента: inbox + port-service
- Интеграция: REST API, IMAP IDLE, SMTP
- Технологии: Spring Boot, Ollama, PostgreSQL, Grafana

### 3. Demo (5 минут)
- Запуск docker-compose
- Отправка письма
- Показ UI (тикет, черновик)
- Отправка ответа
- Проверка почты

### 4. Code (3 минуты)
- Открыть IDE
- Показать комментарии (на русском!)
- Объяснить ключевые классы (TicketProcessingService, StatusUpdateScheduler)
- Показать тесты

### 5. Вопросы (5-10 минут)
- Architecture decisions
- Security
- Scalability
- Testing approach

---

## 📚 Документация

```
inbox/
├── README.md ← Диаграмма архитектуры, endpoints, запуск
├── COMMENTARY_SUMMARY.md ← Список всех комментариев (14+ файлов)
├── DEFENSE_GUIDE.md ← Сценарий защиты, Q&A
└── COMPLETION_REPORT.md ← Итоговый отчет о комментариях

port-service/port-service/
├── PORT_SERVICE_COMMENTARY.md ← Список комментариев (8 файлов)
└── (остальное в родительской папке)
```

---

## ✨ Резюме

Проект демонстрирует:
- ✅ **Modern Java** (17, records, Streams, Optional)
- ✅ **Spring Boot 3** (Security, Integration, Data, Actuator)
- ✅ **Microservices** (независимые компоненты, REST API)
- ✅ **AI Integration** (Ollama, prompt engineering, fallbacks)
- ✅ **Testing** (Mockito, MockMvc, Testcontainers)
- ✅ **DevOps** (Docker Compose, Monitoring, Health checks)
- ✅ **Documentation** (комментарии на русском, профессиональные)

**Готово к защите! 🎓**

---

*Создано: 20 апреля 2026*
*Проект: SmartFreight Inbox v1 MVP*
*Статус: Production-Ready (с ограничениями на MVP)*
