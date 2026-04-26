# Smart Freight Inbox — Week 2 Defense Testing Checklist

## Week 2, Days 8–9: End-to-End Testing on Defense Machine

**Purpose:** Comprehensive validation of all systems before live presentation  
**Duration:** 4–5 hours total  
**Outcome:** Fully functional demo environment, documented issues/workarounds  

---

## Pre-Testing Setup (Day 8, Morning — 30 min)

### Machine Preparation

- [ ] **Update code:** `git pull origin main` on defense machine
- [ ] **Verify Git status:** `git status` should show clean working directory
- [ ] **Check disk space:** At least 20 GB free (Docker images are large)
- [ ] **Verify Docker:** `docker --version` (should be recent)
- [ ] **GPU check:** `nvidia-smi` (confirm GPU is available if using NVIDIA)
- [ ] **Network:** Test internet connectivity: `ping 8.8.8.8`

### Environment Setup

```bash
cd /path/to/inbox
pwd  # confirm you're in the right directory
export DOCKER_BUILDKIT=1  # faster builds
export COMPOSE_DOCKER_CLI_BUILD=1
```

### Browser Preparation

Open these tabs (keep them ready):
1. `http://localhost:8080/login` — UI login page
2. `http://localhost:8080/actuator/health` — health check
3. `http://localhost:9090/targets` — Prometheus scrape targets
4. `http://localhost:3000/d/...` — Grafana dashboard (if available)
5. `http://localhost:5433` → PostgreSQL (via psql or DBeaver)

---

## Phase 1: Docker Startup & Service Health (1–1.5 hours)

### Step 1: Start Docker Compose

```bash
docker compose up --build 2>&1 | tee docker-startup.log
```

**Expected output:**
- Each service logs its startup sequence
- No error messages (warnings are OK)
- Expected startup times:
  - Backend: ~45 sec
  - PostgreSQL: ~15 sec
  - Ollama: ~90 sec (loading models)

**Checkpoint:**
- [ ] All services running without errors
- [ ] No "Container exited" messages
- [ ] Save startup log: `docker-startup.log`

### Step 2: Wait for Ollama Model Loading

**Why:** Ollama must download and load Qwen 2.5 and Llama 3.1 models. This can take 2–3 minutes on first run.

**Monitor:**
```bash
# In another terminal
docker compose logs ollama | grep -i "loaded\|loading\|error"
```

**Expected output:**
```
ollama    | Loaded model: qwen:latest (7.6 GB)
ollama    | Loaded model: llama:latest (4.7 GB)
ollama    | Ready
```

**If models are missing/corruding**, manually pre-pull them:

```bash
docker exec inbox-ollama-1 ollama pull qwen:latest
docker exec inbox-ollama-1 ollama pull llama:latest
```

**Checkpoint:**
- [ ] Both models loaded successfully
- [ ] Ollama logs show no errors

### Step 3: Backend Readiness Check (Wait ~60 sec)

```bash
# Check if backend is responding
for i in {1..30}; do
  curl -s http://localhost:8080/actuator/health && break
  echo "Waiting for backend... ($i/30)"
  sleep 2
done
```

**Expected response:**
```json
{
  "status": "UP",
  "components": {
    "ollama": { "status": "UP" },
    "postgres": { "status": "UP" },
    ...
  }
}
```

**Checkpoint:**
- [ ] HTTP 200 OK
- [ ] Status = "UP"
- [ ] All components listed

### Step 4: Database Connectivity Check (15 min)

```bash
# Check smartfreight DB (inbox)
psql -h localhost -p 5433 -U postgres -d smartfreight \
  -c "SELECT COUNT(*) FROM email_ticket;"

# Expected: Returns a number (e.g., 5, 10, 15 from seed data)
```

```bash
# Check port_system DB (port-service)
psql -h localhost -p 5433 -U postgres -d port_system \
  -c "SELECT COUNT(*) FROM container;"

# Expected: Returns number of containers (should be 20)
```

**If connection fails:**
- Confirm containers are running: `docker ps`
- Check logs: `docker compose logs postgres`
- Restart: `docker compose restart postgres && sleep 30`

**Checkpoint:**
- [ ] Both databases accessible
- [ ] Seed data present (email_ticket, containers)
- [ ] No SQL errors

### Step 5: Prometheus & Grafana Check (Optional, 10 min)

```bash
# Prometheus targets
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[].labels.job'

# Expected: "prometheus", "backend", "ollama", etc.
```

Visit `http://localhost:3000` (Grafana):
- [ ] Login successful (admin / changeme)
- [ ] Dashboards visible
- [ ] Panels rendering (no "No data" errors)

**Checkpoint:**
- [ ] Prometheus scraping 3+ targets
- [ ] Grafana showing metrics

---

## Phase 2: UI Workflow Testing (1–1.5 hours)

### Step 6: Login Page & Authentication

Navigate to `http://localhost:8080/login`

**What you should see:**
- Spring Security login form
- Email field (label: "Email")
- Password field
- "Login" button

**Test:**
1. Try incorrect credentials:
   - Email: `wrong@example.com`, Password: `wrong`
   - Expected: Error message "Invalid email or password"
   - [ ] Error message appears

2. Try correct credentials:
   - Email: `operator@smartfreight.com`
   - Password: `demo123`
   - Expected: Redirect to `/tickets` page
   - [ ] Redirect successful
   - [ ] Session cookie set (check browser dev tools: Application → Cookies)

**Checkpoint:**
- [ ] Invalid credentials rejected
- [ ] Valid credentials accepted
- [ ] Session established

### Step 7: Ticket List Page

After successful login, you should see `/tickets`:

**Expected elements:**
- Title: "Smart Freight Inbox — Tickets"
- Table with columns: ID, From, Subject, Status, Created
- At least 5 rows of sample data
- Filter dropdown (optional): Status filter

**Test filters:**
- [ ] Filter by "NEW" — shows only NEW status tickets
- [ ] Filter by "DRAFT_READY" — shows only DRAFT_READY tickets
- [ ] Filter by "CLOSED" — shows only CLOSED tickets
- [ ] "All" filter — shows all tickets

**Expected tickets** (from V4__seed_test_tickets.sql):
- Multiple entries with different statuses
- Some in DRAFT_READY (already processed by seed)
- Some in NEW (ready to process)

**Checkpoint:**
- [ ] Ticket list loads
- [ ] Sample data visible
- [ ] Filters work correctly

### Step 8: Ticket Detail Page

Click on one NEW ticket:

**Expected page (/tickets/{id}):**
- Original email body (raw text)
- Extracted container number (or empty if NEW)
- Container lookup result (if processed)
- Draft reply (if DRAFT_READY)
- Action buttons: "Approve & Close", "Reject"

**Test with DRAFT_READY ticket** (easier, already processed):
1. Click on a DRAFT_READY ticket
2. [ ] See all fields populated
3. [ ] Draft reply is readable, professional tone
4. [ ] Container lookup successful (location, status visible)

**Test with NEW ticket** (for demo):
1. Click on a NEW ticket
2. [ ] Email body visible
3. [ ] No extraction/draft yet (fields empty or show "Processing...")
4. [ ] Status = NEW

**Checkpoint:**
- [ ] Detail page loads without errors
- [ ] Data presented clearly
- [ ] All fields render correctly

### Step 9: Error Handling (Thymeleaf UI)

Test error scenarios:

**Scenario 1: Navigate to non-existent ticket**
```bash
curl -L http://localhost:8080/tickets/999999
```

**Expected:**
- HTTP 404
- Custom error.html page displays
- Message like "Ticket not found"

**Checkpoint:**
- [ ] Custom error page renders (not whitelabel)
- [ ] Error message is clear

**Scenario 2: Try to access without login**
1. Clear cookies
2. Navigate directly to `http://localhost:8080/tickets`
3. **Expected:** Redirect to `/login`

**Checkpoint:**
- [ ] Redirect to login works
- [ ] Security enforced

---

## Phase 3: REST API Testing (45 min)

### Step 10: REST Auth Endpoint

**Test login via REST:**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"operator@smartfreight.com","password":"demo123"}' | jq
```

**Expected response:**
```json
{
  "status": "ok"
}
```

**Checkpoint:**
- [ ] HTTP 200 OK
- [ ] Response has "status": "ok"

**Test invalid credentials:**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"wrong@example.com","password":"wrong"}' | jq
```

**Expected:**
- HTTP 401 Unauthorized
- Error message "Invalid email or password"

**Checkpoint:**
- [ ] HTTP 401
- [ ] Error handling works

### Step 11: REST Tickets Endpoint

```bash
# List tickets
curl -s http://localhost:8080/api/tickets | jq '.[0]'

# Expected: Array of ticket objects with id, from, subject, status, etc.
```

```bash
# Get specific ticket
curl -s http://localhost:8080/api/tickets/1 | jq

# Expected: Single ticket object with full details
```

**Checkpoint:**
- [ ] GET /api/tickets returns array
- [ ] GET /api/tickets/{id} returns single ticket
- [ ] All fields present

### Step 12: REST Approve/Reject Endpoints

```bash
# Find a DRAFT_READY ticket ID (from earlier steps)
TICKET_ID=5  # Example

# Approve (close) a ticket
curl -X POST http://localhost:8080/api/tickets/$TICKET_ID/approve \
  -H "Content-Type: application/json" | jq

# Expected: HTTP 200, ticket status changes to CLOSED
```

**Verify ticket status changed:**
```bash
curl -s http://localhost:8080/api/tickets/$TICKET_ID | jq '.status'
# Expected: "CLOSED"
```

**Checkpoint:**
- [ ] Approve endpoint works
- [ ] Status updated successfully
- [ ] REST API is functional

---

## Phase 4: Admin Endpoints (15 min)

### Step 13: AdminController /admin/reprocess (Optional)

**Note:** This is localhost-only for security.

```bash
# Trigger reprocess (if running locally)
curl -X POST http://localhost:8080/admin/reprocess | jq

# Expected: HTTP 200, message with count of reprocessed tickets
```

**Expected response:**
```json
{
  "message": "Reprocessed 3 tickets."
}
```

**Checkpoint:**
- [ ] Endpoint accessible from localhost
- [ ] Returns success message
- [ ] Tickets marked for reprocessing

**If running from different machine:**
- Expected: HTTP 403 Forbidden (not localhost)
- [ ] Security check works

---

## Phase 5: Full Demo Workflow (1 hour)

### Step 14: Simulate Email Ingestion

**Option A: HTTP POST (Simulated Email)**

```bash
curl -X POST http://localhost:8080/api/test/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "from": "customer@globalshipping.co.uk",
    "subject": "Container MSCU5555666 Status Update",
    "body": "Hi, we need to know the status of our container MSCU5555666. When will it arrive at Felixstowe?",
    "messageId": "test-'$(date +%s)'@example.com"
  }' | jq
```

**Expected response:**
```json
{
  "status": "ok",
  "ticketId": 99
}
```

**Checkpoint:**
- [ ] HTTP 200 OK
- [ ] New ticket ID returned
- [ ] Message accepted

**Option B: Real IMAP (If Configured)**

If you have `MAIL_IMAP_HOST` configured in `application.yaml`:
1. Send an email to the configured mailbox
2. Wait 10–30 seconds for IMAP IDLE to pick it up
3. Refresh `/tickets` page
4. [ ] New ticket appears

### Step 15: Monitor Processing

**Check backend logs:**
```bash
docker compose logs -f backend | grep -E "TicketProcessing|Extraction|Drafting|email_ticket"
```

**Expected log sequence:**
1. "EmailIngestionService: New email received from..."
2. "TicketProcessingService: Processing ticket..."
3. "AiExtractionService: Extracting container from..."
4. "ContainerLookupService: Found container MSCU..."
5. "AiDraftingService: Generating draft reply..."
6. "TicketProcessingService: Saved ticket status=DRAFT_READY"

**Checkpoint:**
- [ ] Processing logs appear
- [ ] No error messages
- [ ] Processing completes in <15 sec

### Step 16: Verify Ticket in UI

1. Refresh `/tickets` page
2. [ ] New ticket appears at top of list
3. Click on it
4. [ ] Container number is extracted
5. [ ] Draft reply is present
6. [ ] Container lookup successful

**Example expected data:**
```
From: customer@globalshipping.co.uk
Subject: Container MSCU5555666 Status Update

Extracted Container: MSCU5555666
Container Location: Felixstowe Terminal 2
Current Status: AT_PORT
ETA: 2026-04-30

Draft Reply:
Thank you for your inquiry about container MSCU5555666. 
We confirm that your container has arrived at Felixstowe Terminal 2 
and is currently undergoing customs inspection. Expected release date is April 30, 2026.
We will update you as soon as processing is complete.
Best regards...
```

**Checkpoint:**
- [ ] All data visible and accurate
- [ ] Draft quality is professional

### Step 17: Test Operator Actions

**Approve ticket:**
1. Click "Approve & Close"
2. [ ] Confirmation dialog or message
3. [ ] Ticket status changes to CLOSED
4. [ ] Redirect back to ticket list

**Checkpoint:**
- [ ] Approval works
- [ ] Status updated
- [ ] UI reflects change

---

## Phase 6: Metrics & Observability (30 min)

### Step 18: Prometheus Targets

Visit `http://localhost:9090/targets`

**Expected targets:**
- [ ] `prometheus` → UP
- [ ] `backend` (Spring Boot) → UP
- [ ] `ollama` → UP (if scrape config added)

**Check scrape health:**
```bash
curl -s http://localhost:9090/api/v1/query?query=up | jq '.data.result[] | {job: .metric.job, value: .value}'
```

**Expected output:**
```
{
  "job": "prometheus",
  "value": ["1550000000", "1"]
}
{
  "job": "backend",
  "value": ["1550000000", "1"]
}
```

**Checkpoint:**
- [ ] All scrape targets UP
- [ ] No "UNKNOWN" or "DOWN" targets

### Step 19: Grafana Dashboard

Visit `http://localhost:3000` → Go to home or dashboards

**Expected panels:**
- [ ] "Tickets by Status" (pie chart or bar chart)
- [ ] "Processing Duration" (histogram or time series)
- [ ] "Ollama Health" (UP/DOWN indicator)
- [ ] Metrics are populating (not all "No data")

**Checkpoint:**
- [ ] Dashboard loads
- [ ] Panels show data
- [ ] Metrics are realistic

### Step 20: Custom Health Check

```bash
curl -s http://localhost:8080/actuator/health/ollama | jq
```

**Expected:**
```json
{
  "status": "UP",
  "details": {
    "qwen": "loaded",
    "llama": "loaded"
  }
}
```

**Checkpoint:**
- [ ] Custom health indicator works
- [ ] Both models show as loaded

---

## Phase 7: Final System Check (30 min)

### Step 21: Recap Verification

Create a checklist document (`DEFENSE_TEST_REPORT.md`):

```markdown
# Defense Test Report — 2026-04-26

## System Readiness: ✅ PASS

### Services
- [x] Backend Spring Boot (:8080)
- [x] PostgreSQL smartfreight (:5433)
- [x] PostgreSQL port_system (:5433)
- [x] Ollama (:11434)
- [x] Prometheus (:9090)
- [x] Grafana (:3000)

### Workflows
- [x] UI Login
- [x] Ticket List
- [x] Ticket Detail
- [x] Email Ingestion (simulated)
- [x] AI Processing (Qwen + Llama)
- [x] Operator Approval
- [x] REST API Login
- [x] REST API List/Get
- [x] Error Handling (404 custom page)

### Performance
- [x] Backend startup: ~45 sec
- [x] DB connectivity: <100 ms
- [x] Email processing: ~8–10 sec
- [x] UI load times: <1 sec

### Issues Found
- None critical

### Recommendations
- Pre-load Ollama models (90 sec startup)
- Have video backup in case Ollama is slow
- Test on actual presentation machine 1 day before

### Sign-off
Tester: [Your Name]
Date: 2026-04-26
Status: ✅ READY FOR DEFENSE
```

**Checkpoint:**
- [ ] All systems verified
- [ ] Report documented
- [ ] No blocker issues

### Step 22: Cleanup & Documentation

```bash
# Save startup logs
docker compose logs > defense-logs-$(date +%Y-%m-%d).txt

# Shutdown (keep data)
docker compose stop

# (Don't use 'down' yet, we'll restart for rehearsal)
```

**Checkpoint:**
- [ ] Logs backed up
- [ ] Systems halted cleanly

---

## Troubleshooting Guide

| Issue | Symptom | Solution |
|-------|---------|----------|
| Ollama models not loaded | "Connection refused" on port 11434 | `docker compose restart ollama && sleep 90` |
| PostgreSQL won't start | "Database already in use" error | `docker compose down -v && docker compose up` (wipes data) |
| Backend connection pool exhausted | "Connection timeout" in logs | Reduce traffic, restart backend, increase pool size |
| UI shows "No data" | Ticket list is empty | Seed data not loaded; check DB migration status |
| Prometheus scrape fails | "UNKNOWN" status for targets | Check endpoint URLs in `prometheus.yml` |
| Demo email doesn't arrive | IMAP endpoint returns success, but no ticket created | Check `EmailIngestionService` logs, verify IMAP config |
| Grafana dashboards empty | "No data" on all panels | Wait for metrics to accumulate (~1 min), or manually trigger activity |

---

## Sign-Off Checklist

Print this and check off physically:

```
Week 2, Days 8–9: End-to-End Testing Checklist

Date: ____________________
Tester: ____________________

PHASE 1: Docker & Services
[ ] Code pulled (git pull origin main)
[ ] Docker Compose up --build successful
[ ] All services running without errors
[ ] Backend health check (/actuator/health): UP
[ ] PostgreSQL smartfreight: Connected
[ ] PostgreSQL port_system: Connected
[ ] Ollama models loaded (both Qwen + Llama)

PHASE 2: UI Workflow
[ ] Login with correct credentials: ✅ Success
[ ] Login with incorrect credentials: ✅ Rejected
[ ] Ticket list page loads
[ ] Filters work (NEW, DRAFT_READY, CLOSED)
[ ] Ticket detail page loads
[ ] Error page (404) custom HTML rendered
[ ] No SQL errors in logs

PHASE 3: REST API
[ ] POST /api/auth/login with correct credentials: 200 OK
[ ] POST /api/auth/login with wrong credentials: 401 Unauthorized
[ ] GET /api/tickets: 200 OK, array returned
[ ] GET /api/tickets/{id}: 200 OK, single ticket returned
[ ] POST /api/tickets/{id}/approve: 200 OK, status updated

PHASE 4: Admin Endpoints
[ ] POST /admin/reprocess from localhost: 200 OK
[ ] POST /admin/reprocess from remote: 403 Forbidden (security works)

PHASE 5: Demo Workflow
[ ] Send simulated email via POST: 200 OK
[ ] Backend logs show processing: ✅ Yes
[ ] New ticket appears in UI: ✅ Yes
[ ] Container extracted correctly: ✅ Yes
[ ] Draft reply generated: ✅ Yes
[ ] Operator can approve: ✅ Yes
[ ] Status changes to CLOSED: ✅ Yes

PHASE 6: Metrics
[ ] Prometheus targets all UP
[ ] Grafana dashboards rendering
[ ] Custom Ollama health check: ✅ UP

PHASE 7: Final Check
[ ] No critical errors in logs
[ ] Startup time documented (~3 min total)
[ ] Demo workflow timing documented (~8–10 sec per email)
[ ] Test report generated

OVERALL STATUS: ✅ READY FOR DEFENSE

Issues found: ____________________
Workarounds: ____________________
Remaining tasks: ____________________

Sign-off: ____________________
```

---

*Testing Checklist prepared: 2026-04-26*  
*Ready for Week 2, Days 8–9 testing phase*
