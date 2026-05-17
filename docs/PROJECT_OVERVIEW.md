# Smart Freight Inbox — Project Overview

## The Problem

Port logistics companies receive **hundreds of freight-related emails every day**.
Each email typically contains a shipping container number, a status inquiry, or a delivery request from a customer or partner.

Today, this process is entirely **manual**:

1. An operator opens each email one by one
2. They read the email, extract the container number by eye
3. They open the internal system, search for the container
4. They write a reply from scratch — every time, for every email
5. They send it and move to the next one

This creates serious problems:

| Problem | Impact |
|---------|--------|
| Slow response times | Customers wait hours or days for basic status updates |
| Human error | Container numbers misread, wrong containers looked up |
| Operator overload | Staff spend 60–70% of their day on repetitive copy-paste work |
| Inconsistent replies | Every operator writes differently; no standardized communication |
| No traceability | Emails handled ad-hoc, no audit trail, no metrics |
| Scalability ceiling | More cargo volume = proportionally more staff needed |

---

## The Solution

**Smart Freight Inbox** is an AI-powered email processing backend that automates the repetitive parts of this workflow — while keeping a human operator in control of every outgoing response.

The system:

1. **Listens** to the company mailbox continuously via IMAP IDLE (no polling, real-time)
2. **Extracts** the container number from each email using a local LLM (Qwen 2.5)
3. **Looks up** the container in the internal PostgreSQL database
4. **Generates** a professional draft reply using a second LLM (Llama 3.1)
5. **Presents** the draft to a human operator for review and approval

The operator sees a ready-to-send reply, reviews it in seconds, and clicks **Approve** — or **Reject** to trigger reprocessing. Nothing is sent automatically. The AI handles the tedious work; the human makes the final call.

---

## How It Works

```
Incoming email
      │
      ▼
 IMAP IDLE listener (Spring Integration)
      │
      ▼
 Deduplication by Message-ID
      │
      ▼
 Save as EmailTicket (status: NEW)
      │
      ▼
 Synchronous AI pipeline (same thread, after commit)
      │
      ├─► Qwen 2.5 (Ollama): extract container number
      │         │
      │         ▼
      │   PostgreSQL: look up container details
      │         │
      │         ▼
      └─► Llama 3.1 (Ollama): generate draft reply
                │
                ▼
         Save draft (status: DRAFT_READY)
                │
                ▼
         Operator reviews via Thymeleaf UI
                │
         ┌──────┴──────┐
         ▼             ▼
      Approve        Reject
     (CLOSED)    (RETRY_LATER)
```

---

## Key Design Decisions

### On-Premise AI — No Cloud, No Data Leakage

All AI inference runs locally via **Ollama** on company hardware (tested on NVIDIA RTX 5080, 16 GB VRAM).
Freight data — container numbers, customer names, shipment details — never leaves the company network.
This is a hard requirement in logistics due to contracts and GDPR compliance.

### Human-in-the-Loop by Design

The system never sends an email autonomously. Every draft goes through operator approval.
This is intentional: LLMs can make mistakes, and in logistics, a wrong reply about a container's location or ETA has real business consequences.

### Resilient Processing

- If Ollama is temporarily unavailable, regex-based fallback extracts the container number directly from the email body
- Tickets rejected by the operator are retried via `RetryScheduler`
- Each ticket tracks its retry count and processing status
- Optimistic locking protects the ticket row in case of concurrent updates

### Deduplication

IMAP can redeliver the same email after a reconnect. The system deduplicates by `Message-ID` header using a database unique constraint, so the same email is never processed twice — even under concurrent load.

---

## Technology Stack

### Backend
- **Java 17** + **Spring Boot 4.0.5**
- **Spring Integration** — IMAP IDLE mail channel
- **Spring Data JPA** + **Flyway** — schema migrations, typed repositories
- **Spring Security** — session-based form login, BCrypt
- **WebClient** (reactive) — non-blocking calls to Ollama API
- **PostgreSQL** — relational store for tickets, containers, users

### AI
- **Ollama** — local LLM serving (on-premise)
- **Qwen 2.5 (7B)** — container number extraction from unstructured email text
- **Llama 3.1 (8B)** — professional freight reply generation

### Infrastructure
- **Docker Compose** — single-command local deployment
- **Prometheus** + **Grafana** — metrics and dashboards
- **Spring Actuator** — health checks, custom `OllamaHealthIndicator`
- **Testcontainers** — integration tests with real PostgreSQL

### Frontend (v1)
- **Thymeleaf + Bootstrap 5** — server-side rendered operator UI
- Login (form-based, session via JSESSIONID cookie), ticket list, ticket detail, close action
- v2 roadmap: REST API for external clients (`/api/tickets`, `/api/auth/login`) — not available in v1

---

## What Makes This Different

Most email automation tools either:
- Send replies **automatically** (risky in logistics — one wrong reply = customer complaint or dispute)
- Require **cloud AI** (unacceptable for GDPR-sensitive freight data)
- Need **custom email templates** configured per case type (doesn't work for free-text inquiries)

Smart Freight Inbox combines **local AI**, **human oversight**, and **structured status tracking** in a single deployable system — with no external dependencies beyond the company's own infrastructure.

---

## Project Status

| Version | Status | Description |
|---------|--------|-------------|
| **v1 (MVP)** | Feature-complete for demo | Monolith: IMAP IDLE + synchronous AI pipeline + PostgreSQL + Thymeleaf UI + REST API |
| **v2** | Planned | Scale-out: RabbitMQ + Redis + Microsoft Graph API + multi-tenant |

**v1** is a university capstone project demonstrating real-world applicability of local LLMs in enterprise workflows.

**v2** extends the architecture toward production-readiness: replacing in-process synchronous handling with RabbitMQ-backed workers, adding multi-tenancy so multiple freight companies can use the same deployment, and replacing IMAP with Microsoft Graph API for better reliability and Office 365 integration.

---

## Repository

- GitHub: [https://github.com/olegskel7-wq/smart-freight-inbox](https://github.com/olegskel7-wq/smart-freight-inbox)
- Demo user: `operator@smartfreight.com` / `demo123`
- Start with: `docker compose up --build`

---

## Author

Built as a university graduation project exploring practical applications of on-premise large language models in industrial logistics workflows.
