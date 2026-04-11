# Testing LLM Email Workflow — Complete Guide

This document describes how to test the complete email-to-AI-response workflow with real emails.

---

## Prerequisites

### 1. Docker Containers Running

Verify PostgreSQL and Ollama are running:

```powershell
docker ps
```

Expected output:
```
CONTAINER ID   IMAGE                    STATUS
...            postgres:16              Up (healthy)  Port 5433->5432
...            ollama/ollama:0.3.14     Up (healthy)  Port 11434->11434
```

If missing, start them:

```powershell
cd C:\Users\olegs\Downloads\inbox\inbox
docker compose up postgres ollama
```

Wait for Ollama to load models (~2-3 minutes):
```
ollama    | ... successfully loaded model qwen2.5:7b
ollama    | ... successfully loaded model llama3.1:8b
ollama    | Listening on [::]:11434
```

### 2. Spring Boot Application Running

In IntelliJ: **Run → Run 'InboxApplication'**

Check logs for successful startup:
```
INFO  c.s.inbox.InboxApplication - Started InboxApplication in 3.5 seconds
INFO  o.s.boot.tomcat.TomcatWebServer - Tomcat started on port 8080
```

### 3. Ollama Health Check

Verify Ollama is accessible:

```powershell
curl http://localhost:11434/api/tags
```

Response should be:
```json
{
  "models": [
    {"name": "qwen2.5:7b"},
    {"name": "llama3.1:8b"}
  ]
}
```

---

## Option A: Test with Database Test Tickets (Fastest)

### No Real Email Required ✅

1. **View existing test tickets**
   - Open browser: `http://localhost:8080/login`
   - Email: `operator@smartfreight.com`
   - Password: `demo123`

2. **See pre-generated responses**
   - Navigate to `/tickets`
   - You'll see 50 test tickets with various statuses:
     - **DRAFT_READY** (27 tickets) — AI responses already generated
     - **NEW** (10 tickets) — waiting for processing
     - **FAILED** (3 tickets) — processing errors
     - **CLOSED** (3 tickets) — completed by operator

3. **Watch NEW tickets get processed**
   - Open `/tickets` page
   - Look at tickets with status **NEW**
   - Click "View" on any NEW ticket
   - Wait 10-30 seconds
   - Refresh the page (F5)
   - Status should change to **DRAFT_READY**
   - You'll see the AI-generated draft reply

### Example NEW Ticket:

```
From: simon.walker@britlog.co.uk
Subject: Status request: MSCU1234567
Body: Hi, quick status check on MSCU1234567 please. Thanks, Simon
Status: NEW
```

After processing (~20 seconds):
```
Status: DRAFT_READY
Draft Reply:
"Dear Mr Walker,

Thank you for your enquiry regarding container MSCU1234567.

The container is currently IN TRANSIT on Vessel MSC Francesca with ETA 20 April 2026 at 08:00.
We will keep you updated on any changes.

Kind regards,
Smart Freight Inbox Team"
```

---

## Option B: Test with Real Gmail (Advanced)

### Step 1: Create Test Gmail Account

1. Go to https://accounts.google.com/signup
2. Create new account:
   - Email: `smartfreight.test.2026@gmail.com` (or any name)
   - Password: Use strong password (e.g., `TestFreight2026!@#`)
3. Go to https://myaccount.google.com/

### Step 2: Enable 2FA and App Password

1. Navigate to **Security** tab
2. Find **2-Step Verification** → Enable it
3. After enabling, go back to **Security**
4. Find **App passwords** (only visible after 2FA enabled)
5. Select: **Mail** and **Windows Computer**
6. Gmail will generate 16-character password: `abcd efgh ijkl mnop`
7. **Copy the entire password** (including spaces)

### Step 3: Configure IntelliJ Environment Variables

1. In IntelliJ: **Run → Edit Configurations...**
2. Select `InboxApplication` configuration
3. Under **Environment variables**, add:

```
IMAP_HOST=imap.gmail.com
IMAP_PORT=993
IMAP_USER=smartfreight.test.2026@gmail.com
IMAP_PASSWORD=abcd efgh ijkl mnop
```

4. Click **OK** and **Apply**

### Step 4: Restart Application

1. Stop the running application (red stop button)
2. Wait 2 seconds
3. Start again: **Run → Run 'InboxApplication'**

Check logs for:
```
INFO  c.s.inbox.config.ImapConfig - IMAP configured and enabled
INFO  o.s.i.endpoint.EventDrivenConsumer - started bean 'imapIdleFlow'
```

If you see this → IMAP is listening! ✅

### Step 5: Send Test Email

From your **main email account**, send to `smartfreight.test.2026@gmail.com`:

```
To: smartfreight.test.2026@gmail.com
Subject: Urgent: Container status inquiry MSCU1234567
Body:

Dear Smart Freight Team,

Could you please provide an update on the status of container MSCU1234567?
We have a customer awaiting delivery and need to confirm the ETA.

Best regards,
John Harris
FreightCo Ltd
```

### Step 6: Watch Processing in Real-Time

1. Open `http://localhost:8080/login`
2. Refresh `/tickets` page
3. You should see the email appear within 10 seconds (IMAP IDLE polling)
4. Status will be **NEW**
5. Within 20-30 seconds, AI will process it
6. Status will change to **DRAFT_READY**
7. Click "View" to see the generated response

---

## How LLM Processing Works

### Architecture Flow

```
Real Email (Gmail)
    ↓
IMAP IDLE Listener (EmailIngestionService)
    ↓
Deduplication (by Message-ID)
    ↓
[NEW] ticket created
    ↓
TicketProcessingService (orchestrator)
    ├─ Step 1: AiExtractionService → Qwen 2.5
    │         Extract container number (MSCU1234567)
    │         Timeout: 150 seconds
    │         Fallback: Regex if Ollama fails
    │
    ├─ Step 2: ContainerLookupService
    │         Query database for container data
    │         (status, ETA, location)
    │
    └─ Step 3: AiDraftingService → Llama 3.1
              Generate professional reply
              Uses Qwen's extracted container + DB data
              Timeout: 150 seconds
              Fallback: Static template if Ollama fails
    ↓
[DRAFT_READY] ticket with response saved
    ↓
Operator reviews in UI
    ↓
Operator clicks "Send" or "Edit" (future feature)
```

### LLM Services Details

#### **AiExtractionService (Qwen 2.5)**

- **File**: `src/main/java/.../service/AiExtractionService.java`
- **Model**: `qwen2.5:7b`
- **Task**: Extract ISO 6346 container number from email
- **Format**: 4 uppercase letters + 7 digits (e.g., `MSCU1234567`)
- **Prompt**: "Extract ONLY the container number. Return 'NOT_FOUND' if absent."
- **Timeout**: 150 seconds
- **Fallback**: Regex pattern `[A-Z]{4}\d{7}`

#### **AiDraftingService (Llama 3.1)**

- **File**: `src/main/java/.../service/AiDraftingService.java`
- **Model**: `llama3.1:8b`
- **Task**: Generate professional email response
- **Input**: Original email + container data (status, ETA, location)
- **Prompt**: "Write concise, professional reply. Don't make up info not in container data."
- **Timeout**: 150 seconds
- **Fallback**: Static template with container details

#### **Orchestrator (TicketProcessingService)**

- **File**: `src/main/java/.../service/TicketProcessingService.java`
- **Retries**: 3 attempts on optimistic lock conflict
- **Error Handling**: 
  - `DRAFT_READY` = success
  - `FAILED` = max retries exhausted or unexpected error
  - `RETRY_LATER` = temporary error (can be manually retried)

---

## Monitoring LLM Activity

### Check Application Logs

In IntelliJ Console, filter for LLM processing:

Search for:
```
AiExtractionService
AiDraftingService
TicketProcessingService
```

Example log output:
```
[ticket:12345] INFO  c.s.inbox.service.AiExtractionService - Sending extraction request to Ollama model qwen2.5:7b
[ticket:12345] INFO  c.s.inbox.service.AiExtractionService - Qwen raw response: 'MSCU1234567'
[ticket:12345] INFO  c.s.inbox.service.AiExtractionService - Container number extracted by LLM: MSCU1234567
[ticket:12345] INFO  c.s.inbox.service.AiDraftingService - Sending drafting request to Ollama model llama3.1:8b
[ticket:12345] INFO  c.s.inbox.service.AiDraftingService - Draft generated successfully (487 chars)
[ticket:12345] INFO  c.s.inbox.service.TicketProcessingService - Ticket processing completed: DRAFT_READY
```

### Check Ollama API Directly

```powershell
# List loaded models
curl http://localhost:11434/api/tags

# Test extraction model
$body = @{
    model = "qwen2.5:7b"
    prompt = "Extract container: MSCU1234567"
    stream = $false
} | ConvertTo-Json

curl -X POST http://localhost:11434/api/generate `
     -Header "Content-Type: application/json" `
     -Body $body
```

### Monitor Processing Time

Average times (on RTX 5080):
- **Extraction (Qwen 2.5)**: 5-15 seconds
- **Drafting (Llama 3.1)**: 10-25 seconds
- **Total**: 20-40 seconds

---

## Troubleshooting

### Problem: "IMAP not configured — adapter disabled"

**Solution:**
- Verify environment variables are set correctly
- Restart application after changing environment variables
- Check logs: should say "IMAP configured and enabled"

### Problem: Ollama returns "Connection refused"

**Solution:**
```powershell
# Verify Ollama container is running
docker ps | grep ollama

# Check Ollama logs
docker logs ollama

# Verify port 11434 is accessible
curl http://localhost:11434/api/tags
```

### Problem: Processing takes longer than expected (>60 seconds)

**Possible causes:**
- GPU memory full (other processes using GPU)
- Ollama loading model for first time
- Network latency
- Timeout configured too low

**Solution:**
- Check Docker resource allocation
- Wait for first request (models load into GPU memory)
- Increase timeout in WebClientConfig if needed

### Problem: "No container number found" (FAILED status)

**Diagnosis:**
- Email body doesn't contain ISO 6346 format number
- Qwen couldn't extract and regex failed
- Check logs for: "No container number found in email body"

**Solution:**
- Add container number to email clearly (e.g., "MSCU1234567")
- Check extracted_container_number field is NULL in database

### Problem: Draft reply is generic template

**Cause:**
- Ollama returned error or empty response
- Fallback template was used

**Solution:**
- Check Ollama logs: `docker logs ollama`
- Verify models are loaded: `ollama list`
- Restart Ollama: `docker restart ollama`

---

## Testing Checklist

Use this checklist to verify end-to-end functionality:

- [ ] PostgreSQL running and healthy
- [ ] Ollama running with both models loaded
- [ ] Spring Boot application started without errors
- [ ] Login works (`operator@smartfreight.com` / `demo123`)
- [ ] `/tickets` page loads with existing tickets
- [ ] At least one NEW ticket visible
- [ ] After 30 seconds, NEW ticket status changes to DRAFT_READY
- [ ] Draft reply is professional and relevant to the email
- [ ] Draft mentions the correct container number
- [ ] Draft mentions correct status/ETA if container found
- [ ] Application logs show Qwen and Llama requests/responses

---

## Next Steps

After verifying LLM workflow:

1. **Send more test emails** with various scenarios:
   - Valid container number → should get accurate response
   - Invalid/unknown container → should get "not found" response
   - No container number → should fail safely
   - Non-English email → should respond in same language

2. **Monitor performance**:
   - Measure processing time
   - Check GPU memory usage
   - Verify Ollama can handle parallel requests

3. **Test error scenarios**:
   - Stop Ollama mid-processing → should use fallback template
   - Send duplicate emails → should deduplicate by Message-ID
   - Manually change ticket status in DB → should respect status guards

4. **Review generated responses**:
   - Are they professional?
   - Do they match customer inquiry?
   - Are they helpful and factual?

---

## Email Examples for Testing

### Example 1: Standard Status Query

```
From: john@company.com
To: smartfreight.test.2026@gmail.com
Subject: Container MSCU1234567 - Status Update

Dear Team,

Can you please provide an update on container MSCU1234567?
We need to know the ETA for arrival at Felixstowe.

Thanks,
John Smith
```

**Expected Response:**
- Qwen extracts: `MSCU1234567`
- Llama generates response mentioning vessel, ETA, location

### Example 2: Multiple Containers

```
From: ops@logistics.com
To: smartfreight.test.2026@gmail.com
Subject: Status Check - Multiple Containers

Hello,

I need updates on:
1. HLXU8764321
2. CMAU5551234

Which one is at Felixstowe?

Thanks
```

**Expected Response:**
- Qwen extracts first container: `HLXU8764321`
- Llama provides response for that container
- (Note: only first container processed; future enhancement: process all)

### Example 3: Unclear Request

```
From: customer@shipper.com
To: smartfreight.test.2026@gmail.com
Subject: Shipment Question

Hi there,

We have a shipment coming but forgot the container number.
Can you help us find it?

Regards
```

**Expected Response:**
- Qwen returns: `NOT_FOUND`
- Llama generates generic response asking for more info

---

## Logs to Watch

Key log patterns that indicate successful LLM processing:

```
✅ GOOD:
[ticket:N] INFO  AiExtractionService - Container number extracted by LLM: MSCU1234567
[ticket:N] INFO  AiDraftingService - Draft generated successfully (XXX chars)
[ticket:N] INFO  TicketProcessingService - Ticket processing completed: DRAFT_READY

❌ PROBLEMS:
[ticket:N] ERROR Extraction call to Ollama failed
[ticket:N] WARN  Ollama returned null response during drafting
[ticket:N] INFO  TicketProcessingService - Ticket processing failed → FAILED
```

---

## Performance Baseline (RTX 5080)

| Component | Time | Notes |
|-----------|------|-------|
| Email ingestion | < 1 sec | IMAP IDLE polling |
| Qwen extraction | 5-15 sec | First request loads model (~5 sec), subsequent < 10 sec |
| DB lookup | < 50 ms | PostgreSQL query |
| Llama drafting | 10-25 sec | Depends on email length |
| **Total** | **20-40 sec** | Average end-to-end |

---

## References

- [Ollama Documentation](https://github.com/ollama/ollama)
- [Qwen 2.5 Model Card](https://huggingface.co/Qwen/Qwen2.5-7B)
- [Llama 3.1 Model Card](https://huggingface.co/meta-llama/Llama-3.1-8b)
- [Spring Boot WebClient Docs](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/reactive/function/client/WebClient.html)

---

**Last Updated:** April 11, 2026
**Version:** v1.0
