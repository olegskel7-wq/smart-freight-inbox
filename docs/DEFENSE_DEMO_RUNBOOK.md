# Smart Freight Inbox — Defense Demo Runbook

**Purpose:** Step-by-step walkthrough for live demo during thesis defense  
**Target Duration:** 5–7 minutes  
**Audience:** Industry professionals (logistics, tech, potential investors)  
**Contingency:** Video recording backup if live demo fails  

---

## Pre-Demo Setup (Do 30 minutes before presentation)

### Checklist

- [ ] Clone latest code: `git pull origin main` on defense machine
- [ ] Start Docker Compose: `docker compose up --build` from `inbox/` directory
- [ ] Wait for all services to be healthy (2–3 minutes)
- [ ] Test backend health: `curl http://localhost:8080/actuator/health`
- [ ] Open browser tabs (keep them open, ready to switch):
  1. `http://localhost:8080/login` — Thymeleaf UI (login page)
  2. `http://localhost:8080/tickets` — Ticket list (post-login)
  3. `http://localhost:9090` — Prometheus (optional, for metrics)
  4. `http://localhost:3000` — Grafana (optional, for dashboards)
- [ ] Have sample email ready (see "Sample Test Email" section below)
- [ ] Have terminal open with `docker compose logs` in one pane, ready to show ingestion logs

### Expected Startup Times

| Service | Port | Expected Time | Health Check |
|---------|------|----------------|--------------|
| Backend (Spring Boot) | 8080 | 30–45 sec | `curl http://localhost:8080/actuator/health` |
| PostgreSQL (smartfreight) | 5433 | 10–15 sec | `psql -h localhost -p 5433 -U postgres` |
| PostgreSQL (port_system) | 5433 | 10–15 sec | Same as above |
| Ollama | 11434 | 60–90 sec | `curl http://localhost:11434/api/tags` |
| Prometheus | 9090 | 15–20 sec | `curl http://localhost:9090/-/healthy` |
| Grafana | 3000 | 20–30 sec | Visit `http://localhost:3000` |

**If Ollama is slow:** Skip Ollama-dependent steps, use screenshots or pre-recorded video.

---

## Demo Script (5–7 minutes)

### Step 1: Login to UI (30 seconds)

**What to show:**
- Click on `http://localhost:8080/login` tab
- Show login form (email + password fields)
- Emphasize: Form login via Spring Security, session cookie-based

**Actions:**
1. Enter email: `operator@smartfreight.com`
2. Enter password: `demo123`
3. Click "Login"
4. Wait for page redirect to `/tickets`

**What happens behind the scenes (say this out loud):**
"The system authenticates the user against the `app_user` table in PostgreSQL. Password is verified via BCrypt hashing. No plaintext passwords. Once authenticated, a secure session cookie is issued."

**Expected result:**
- Redirect to `/tickets` page showing list of tickets

---

### Step 2: Show Ticket List UI (30 seconds)

**What to show:**
- Ticket list with columns: ID, From, Subject, Status, Created
- Filter dropdown (optional): Status = NEW, DRAFT_READY, CLOSED, etc.
- Example tickets from seed data (V4__seed_test_tickets.sql)

**Point out:**
- Most tickets are in `DRAFT_READY` status (already processed)
- Some are `NEW` (ready to process)
- Human-readable format: operator can see what needs action

**What to say:**
"The operator dashboard shows all incoming tickets. Here you can see tickets at different stages: some are ready for review, others are still being processed. The status machine is fully transparent."

**Expected result:**
- List of 5–10 demo tickets visible

---

### Step 3: Send Test Email (1.5–2 minutes)

**Choice A: Real IMAP (if configured)**

Prerequisite: Have a Gmail/Outlook account configured in `application.yaml` for IMAP.

**Actions:**
1. Show the test email you're about to send: [paste into terminal or show screenshot]
2. Send via: `curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"email":"test@example.com", "password":"testpass"}'` (optional, skip if not relevant)
3. Or: Send from your personal email account to the configured inbox

**Timing:** Email delivery can take 10–30 seconds depending on mail server.

**What to say:**
"I'm sending a test email with a container number embedded in the body. The system will pick it up via IMAP IDLE—that means real-time ingestion without polling. No delays."

---

**Choice B: Simulated POST (Faster, Recommended for Demo)**

If IMAP is unreliable, use HTTP POST to simulate email ingestion.

**Actions:**

1. Have prepared test email JSON:

```bash
curl -X POST http://localhost:8080/api/test/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "from": "customer@example.com",
    "subject": "Container MSCU1234567 Status Update",
    "body": "Hi, we are inquiring about the status of our container MSCU1234567. We need to know the current location and expected delivery date. Thanks.",
    "messageId": "test-'$(date +%s)'@example.com"
  }'
```

2. Paste into terminal and press Enter
3. Watch for response: `{"status": "ok", "ticketId": 123}`

**What to say:**
"I'm simulating an incoming email with a container number. The system will process it end-to-end: extract the container number, look it up, and generate a draft reply."

**Expected result:**
- HTTP 200 OK
- New ticket visible in UI within 2–3 seconds

---

### Step 4: Refresh Ticket List & Show New Ticket (30 seconds)

**Actions:**
1. Refresh `/tickets` page (F5 or Cmd+R)
2. Look for a new ticket with status `NEW` or `PROCESSING`

**What to say:**
"The ticket has been ingested. It's now in the system. Let's see what the AI extracted from the email."

**Expected result:**
- New ticket appears at top of list with ID, sender, subject

---

### Step 5: Click on Ticket Detail (45 seconds)

**Actions:**
1. Click on the new ticket ID or subject line
2. Wait for page load (should be <1 second)
3. Show `/tickets/{id}` page with:
   - Original email body
   - Extracted container number (e.g., `MSCU1234567`)
   - Container lookup result (location, status, ETA)
   - AI-generated draft reply

**What to say:**
"Here's the full ticket detail. At the top, you see the original email body. Below that, the system extracted the container number using Qwen 2.5 (a 7B-parameter LLM running locally via Ollama). That's `MSCU1234567`. It then looked up the container in our database and found it's at the Felixstowe terminal. And here's the draft reply, generated by Llama 3.1."

**Key points to highlight:**
1. **Extraction:** Container number is visible and correct
2. **Lookup:** Port location is accurate (from port-service DB)
3. **Draft quality:** Reply is professional, natural language (not template)

**Expected result:**
- All three pieces of information visible and correct
- Draft reply is 1–2 paragraphs, well-written

---

### Step 6: Operator Action — Approve Ticket (1 minute)

**Actions:**
1. Scroll down to the action buttons
2. Show two buttons: "Approve & Close" and "Reject"
3. Click "Approve & Close"
4. Show confirmation dialog or redirect to ticket list
5. Show that ticket status is now `CLOSED`

**What to say:**
"The operator reviews the draft—it looks good, so they click 'Approve & Close'. The ticket is marked CLOSED, and in a production system, the email reply would be sent via SMTP. This is human-in-the-loop by design. Nothing goes out without approval."

**Expected result:**
- Ticket status changes to `CLOSED`
- Possible redirect back to ticket list
- Success message displayed

---

### Step 7: Show Retry Logic (Optional, 30–45 seconds)

**If time allows:**

1. Go back to ticket list
2. Filter for `RETRY_LATER` status
3. Show at least one ticket in this state
4. Explain: "If the operator had clicked 'Reject' instead, the ticket would be marked RETRY_LATER. The RetryScheduler picks these up every 2 minutes and reprocesses them. Or an operator can manually trigger `/admin/reprocess`."

**What to say:**
"The system handles rejections gracefully. If the operator isn't happy with the AI draft, they click Reject. The ticket goes into RETRY_LATER, and it gets reprocessed later. Or an admin can manually reprocess all failed tickets with a single endpoint. No data is lost, nothing crashes."

**Expected result:**
- Show at least one ticket in `RETRY_LATER` or `FAILED` status (from seed data)

---

### Step 8: Metrics & Observability (Optional, 30–45 seconds)

**If time allows:**

1. Open `http://localhost:9090` (Prometheus)
2. Show "Targets" page — confirm all services are being scraped
3. Or open `http://localhost:3000` (Grafana)
4. Show dashboard with:
   - Tickets by status (pie chart)
   - Processing duration (histogram)
   - Ollama health status

**What to say:**
"The system is fully observable. We have Prometheus scraping metrics from every component, and Grafana visualizing them. In production, you'd set up alerts: if processing time exceeds 10 seconds or Ollama goes down, the team is notified instantly."

**Expected result:**
- Prometheus shows all services as "Up"
- Grafana dashboard shows correct metrics

---

## Sample Test Email (Ready to Use)

### Option 1: Plain Text Email for HTTP POST

```json
{
  "from": "john.smith@globalshipping.co.uk",
  "subject": "Urgent: Container MSCU9876543 Status Request",
  "body": "Hello,\n\nWe are writing to inquire about the current status and location of our container MSCU9876543. It was due to arrive at Felixstowe terminal on April 20th. We need to arrange inland transport and require updated ETA.\n\nPlease advise.\n\nThank you,\nJohn Smith\nGlobal Shipping Ltd",
  "messageId": "test-container-inquiry-001@globalshipping.co.uk"
}
```

### Option 2: Email via Real IMAP

If using real Gmail:

**Subject:** `Container TESU5432109 - Status Check`

**Body:**
```
Hi there,

I hope this email finds you well. We're trying to track our container TESU5432109 which is in your port. Can you give us an update on where it is right now and when we can expect to pick it up?

Looking forward to hearing from you.

Best regards,
Alice Johnson
```

---

## Fallback Strategies (If Something Goes Wrong)

### Scenario 1: Ollama is Slow (Taking >10 seconds)

**Problem:** AI models are taking too long to respond.

**Solution:**
1. Show the code fallback mechanism on slides (regex extraction, template drafting)
2. Say: "In a real system, if Ollama is slow, the system uses a regex pattern to extract the container number immediately. No timeout, no blocking."
3. Continue demo with screenshots of expected output
4. Play video of full workflow (pre-recorded)

### Scenario 2: IMAP Connection Failed

**Problem:** Email ingestion didn't work.

**Solution:**
1. Switch to the HTTP POST method (simulated email)
2. Say: "Let me demonstrate with a simulated email instead. In production, you'd have a real mailbox. For demo purposes, we're using a REST endpoint to trigger ingestion."
3. Continue with POST request

### Scenario 3: Database Connection Failed

**Problem:** PostgreSQL is not responding.

**Solution:**
1. Check Docker logs: `docker compose logs postgres`
2. Restart database: `docker compose restart postgres`
3. Wait 30 seconds and retry
4. If still failing: Play pre-recorded video of the workflow

### Scenario 4: Complete Demo Failure

**Fallback:**
1. Have a pre-recorded screen video (5 minutes) showing full workflow
2. Explain: "Due to technical issues, I'm showing you a recording of the system running smoothly."
3. Continue with Q&A based on slides and video

**Pre-record this video:**
- Show all 8 steps (login, ticket list, send email, detail page, AI results, approve, retry, metrics)
- Use good lighting and clear mouse movements
- Narrate as you go
- Export as MP4 (1080p, 30 fps)

---

## Key Talking Points (Emphasize These)

| Point | Why It Matters |
|-------|----------------|
| **Real-time IMAP IDLE** | No polling, true event-driven |
| **Local Ollama** | On-premise, no data leakage, GDPR-safe |
| **Deduplication by Message-ID** | Prevents duplicate processing even under load |
| **Human-in-the-loop** | Nothing sent without operator approval (risk mitigation) |
| **Graceful fallbacks** | System never crashes, always shows something useful |
| **Optimistic locking** | Safe under concurrent updates |
| **Clear status machine** | Operator knows exactly where each ticket is |

---

## Timing Breakdown

| Section | Time | Notes |
|---------|------|-------|
| Setup | 30 sec | Already done pre-demo |
| Step 1: Login | 30 sec | Quick, builds confidence |
| Step 2: Ticket list | 30 sec | Shows UI clarity |
| Step 3: Send email | 1–1.5 min | Longest step (email ingestion) |
| Step 4: Refresh & show new ticket | 30 sec | Demonstrate real-time |
| Step 5: Ticket detail | 1 min | Highlight AI extraction, lookup, draft |
| Step 6: Approve | 30 sec | Show operator control |
| Step 7: Retry logic (optional) | 45 sec | Optional, if time |
| Step 8: Metrics (optional) | 45 sec | Optional, nice-to-have |
| **Total** | **5–7 min** | Flexible based on questions |

---

## Docker Compose Commands (Quick Reference)

```bash
# Start all services
docker compose up --build

# Check logs (all services)
docker compose logs -f

# Check specific service
docker compose logs -f backend
docker compose logs -f postgres
docker compose logs -f ollama

# Restart a service
docker compose restart backend

# Stop everything
docker compose down

# Clean up volumes (nuclear option)
docker compose down -v
```

---

## Quick Health Checks (Run Before Demo)

```bash
# Backend health
curl http://localhost:8080/actuator/health

# PostgreSQL connectivity
psql -h localhost -p 5433 -U postgres -c "SELECT 1"

# Ollama availability
curl http://localhost:11434/api/tags

# Prometheus targets
curl http://localhost:9090/api/v1/targets
```

---

## Q&A Preparation (Likely Questions During/After Demo)

| Q | A | Source |
|---|---|--------|
| Why local LLMs? | On-premise = no GDPR issues, no API costs, no data leakage | Slide 8, Backup 20 |
| How accurate? | Extraction: 95%+, fallback is regex. Drafting: 100% (human approved) | Slide 8 |
| What if Ollama fails? | Fallback to regex extraction + template drafting, system never crashes | Slide 9, Backup 18 |
| How to scale this? | v1 is sync single-instance. v2 uses RabbitMQ + workers for horizontal scaling | Slide 7, Backup 19 |
| Can this integrate with our email? | v1 uses IMAP (any mailbox). v2 planned for Graph API (Office 365, Outlook) | Slide 6, Q&A doc |
| Cost? | Single GPU machine (~$2–3k) handles ~50–100 emails/day. v2 scales to 1000+/day with 2 GPUs | Backup 19 |
| How to avoid duplicates? | Message-ID unique constraint on email_ticket table, even under concurrent load | Backup 17 |
| GDPR-safe? | Yes—all on-premise, no external APIs, full audit trail, data retention policy configurable | Backup 20 |

---

## Emergency Contact List (For Demo Day)

- **If Docker fails:** Restart Docker daemon, pull latest code
- **If Ollama is unresponsive:** Restart Ollama service, or use video fallback
- **If PostgreSQL won't connect:** Check credentials in application.yaml, restart container
- **If UI doesn't load:** Clear browser cache, check backend logs
- **If email doesn't arrive:** Use HTTP POST method instead of IMAP

---

## Printable Handout (Optional)

Consider printing one page for audience:

```
Smart Freight Inbox — Defense Demo Summary

Key Metrics:
• Processing time: 5–10 sec (extraction + lookup + drafting)
• Email throughput: 50–100/day (v1), 1000+/day (v2)
• Error rate: <2% (with fallbacks)
• Accuracy: 95%+ AI extraction, 100% operator-approved output

Tech Stack:
• Backend: Spring Boot 4.0.5
• Models: Qwen 2.5 (extraction), Llama 3.1 (drafting)
• Database: PostgreSQL 16
• Infrastructure: Docker Compose, on-premise

GitHub: github.com/olegskel7-wq/smart-freight-inbox
Demo credentials: operator@smartfreight.com / demo123
```

---

*Demo Runbook prepared: 2026-04-26*  
*Ready for use during thesis defense*
