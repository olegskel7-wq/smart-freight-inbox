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

> **Примечание v1:** REST API (`/api/auth/login`, `/api/tickets`) запланирован для v2.
> В текущей версии UI полностью основан на Thymeleaf (server-side HTML).
> Для тестирования через Postman используйте Thymeleaf UI эндпоинты ниже.

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
2. **Аутентификация:** Spring Security session (cookie `JSESSIONID`) через form login
3. **CSRF:** Thymeleaf формы автоматически включают CSRF-токен (`th:action`)
4. **Localhost admin:** `/admin/reprocess` доступна только с IP 127.0.0.1

---

## Варианты ID для тестирования

Замени `1` на реальный ID тикета из таблицы на странице `GET /tickets` в браузере.
