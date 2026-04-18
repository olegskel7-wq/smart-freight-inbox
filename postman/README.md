# Postman Collection — Smart Freight Inbox v1

## Использование

1. **Скачайте Postman** с https://www.postman.com/downloads/
2. **Импортируйте коллекцию:**
   - Откройте Postman
   - Нажмите `File` → `Import`
   - Выберите файл `Smart-Freight-Inbox.postman_collection.json`
3. **Запустите приложение:**
   ```bash
   docker-compose up -d
   # или в IntelliJ: Run → Edit Configurations → Run
   ```
4. **Тестируйте запросы:** откройте коллекцию в Postman и нажимайте "Send"

---

## Структура коллекции

### Auth
- **Login (API)** — `POST /api/auth/login`
  - Получить статус при вводе demo@smartfreight.com / demo123

### Tickets (REST API)
- **List all tickets** — `GET /api/tickets`
- **Get ticket by ID** — `GET /api/tickets/1`
- **Approve ticket** — `POST /api/tickets/1/approve` (статус → CLOSED)
- **Reject ticket** — `POST /api/tickets/1/reject` (статус → RETRY_LATER)

### Thymeleaf UI (Form)
- **Login page** — `GET /login` (вернёт HTML)
- **Login form** — `POST /login` (редирект на /tickets после успеха)
- **List tickets** — `GET /tickets` (HTML таблица)
- **Ticket detail** — `GET /tickets/1` (HTML детали + форма редактирования)
- **Update draft & close** — `POST /tickets/1/draft` (сохранить черновик и закрыть)
- **Close ticket** — `POST /tickets/1/close` (просто закрыть)
- **Logout** — `POST /logout` (удалить сессию)

### Admin
- **Reprocess tickets** — `POST /admin/reprocess` (доступно только с localhost)

### Observability
- **Health check** — `GET /actuator/health` (статус Ollama, DB, приложения)
- **Prometheus metrics** — `GET /actuator/prometheus` (метрики в Prometheus формате)

---

## Важные замечания

1. **Порт:** приложение слушает `8080`
2. **Аутентификация для REST API:** использует Spring Security session (cookie)
3. **CSRF:** Thymeleaf формы требуют CSRF токена (автоматический)
4. **Localhost admin:** `/admin/reprocess` доступна только с IP 127.0.0.1

---

## Варианты ID для тестирования

Замени `1` на реальный ID тикета:
```bash
# Посмотреть все ID
GET /api/tickets
```

Ответ покажет список тикетов с ID.
