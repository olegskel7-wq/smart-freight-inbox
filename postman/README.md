# Postman Collection — Smart Freight Inbox v1

## Usage

1. **Download Postman** from https://www.postman.com/downloads/
2. **Import the collection:**
   - Open Postman
   - Click `File` → `Import`
   - Select the file `Smart-Freight-Inbox.postman_collection.json`
3. **Start the application:**
   ```bash
   docker-compose up -d
   # or in IntelliJ: Run → Edit Configurations → Run
   ```
4. **Test requests:** open the collection in Postman and click "Send"

---

## Collection Structure

> **v1 note:** The REST API (`/api/auth/login`, `/api/tickets`) is planned for v2.
> In the current version the UI is fully Thymeleaf-based (server-side HTML).
> Use the Thymeleaf UI endpoints below for testing via Postman.

### Thymeleaf UI (Form)
- **Login page** — `GET /login` (returns HTML)
- **Login form** — `POST /login` (redirects to /tickets on success)
- **List tickets** — `GET /tickets` (HTML table)
- **Ticket detail** — `GET /tickets/1` (HTML detail + edit form)
- **Update draft & close** — `POST /tickets/1/draft` (save draft and close)
- **Close ticket** — `POST /tickets/1/close` (close without sending)
- **Logout** — `POST /logout` (invalidate session)

### Admin
- **Reprocess tickets** — `POST /admin/reprocess` (accessible from localhost only)

### Observability
- **Health check** — `GET /actuator/health` (Ollama, DB and app status)
- **Prometheus metrics** — `GET /actuator/prometheus` (metrics in Prometheus format)

---

## Important Notes

1. **Port:** the application listens on `8080`
2. **Authentication:** Spring Security session (cookie `JSESSIONID`) via form login
3. **CSRF:** Thymeleaf forms automatically include the CSRF token (`th:action`)
4. **Localhost admin:** `/admin/reprocess` is accessible from IP 127.0.0.1 only

---

## Test IDs

Replace `1` with a real ticket ID from the table on the `GET /tickets` page in your browser.
