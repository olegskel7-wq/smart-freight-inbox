# Real IMAP for LLM testing (no code changes)

This guide matches the project plan: enable IMAP by environment variables only. Code paths: [`application.yaml`](../src/main/resources/application.yaml) and [`ImapConfig.java`](../src/main/java/com/smartfreight/inbox/config/ImapConfig.java).

## Provider choice (recommended: Mailtrap)

| Provider | Use case | Password type |
|----------|----------|-----------------|
| **Mailtrap Email Testing** | Safest for demos; real IMAP; no real recipients | Inbox-specific password from Mailtrap UI |
| **Outlook / Microsoft 365** | Real mailbox | Account password or Microsoft app password if MFA |
| **Gmail** | Not recommended without code changes | Requires OAuth2 for IMAP in most cases |

**Recommendation:** use **Mailtrap** for LLM pipeline tests on your PC.

---

## Step 1 — Mailtrap credentials

1. Sign in at [https://mailtrap.io](https://mailtrap.io)
2. **Email Testing** → **Inboxes** → open your inbox (or create one)
3. Tab **SMTP / IMAP** → select **IMAP**
4. Copy: **Host**, **Port**, **Username**, **Password**

Typical host: `sandbox.imap.mailtrap.io`, port: `993`.

---

## Step 2 — IntelliJ Run Configuration (required)

1. **Run** → **Edit Configurations…**
2. Select **InboxApplication** (or your Spring Boot run config)
3. **Environment variables** → `…` → add:

| Name | Value |
|------|--------|
| `IMAP_HOST` | From Mailtrap (e.g. `sandbox.imap.mailtrap.io`) |
| `IMAP_PORT` | `993` |
| `IMAP_USER` | From Mailtrap |
| `IMAP_PASSWORD` | From Mailtrap |

4. **Apply** → **OK**
5. **Stop** then **Run** the application

**Activation rule in code:** IMAP starts only if `host != imap.example.com` **and** `password != changeme`. Your Mailtrap password must not literally be `changeme`.

---

## Step 3 — Outlook alternative

If you use Outlook instead of Mailtrap:

| Name | Value |
|------|--------|
| `IMAP_HOST` | `outlook.office365.com` |
| `IMAP_PORT` | `993` |
| `IMAP_USER` | your full email address |
| `IMAP_PASSWORD` | your password or Microsoft app password (MFA) |

Enable IMAP in Outlook / Microsoft account mail settings.

---

## Step 4 — Infrastructure before testing LLM

From project root:

```powershell
docker compose up -d postgres ollama
```

Check Ollama models (browser or curl):

```text
http://localhost:11434/api/tags
```

You should see `qwen2.5:7b` and `llama3.1:8b` (first run may take minutes while models load).

---

## Step 5 — Verify IMAP in logs

After restart **with** valid `IMAP_*` variables:

- You should **not** see: `IMAP not configured — adapter disabled`
- You should see Spring Integration mail flow started, e.g. beans related to `imapIdleFlow` / `EventDrivenConsumer` for the mail adapter

If you still see `adapter disabled`, the app is still using defaults: check that variables are on the **same** Run Configuration you use to start the app.

---

## Step 6 — Send a test message (manual)

In Mailtrap: **Send test email** (or use their UI to inject a message).

Include a valid ISO 6346 container number from seed data, e.g. `MSCU1234567`, in subject or body.

Example body:

```text
Subject: Status for container MSCU1234567

Please confirm ETA for MSCU1234567.
```

---

## Step 7 — Verify in UI (manual)

1. Open `http://localhost:8080/login` — demo user from README
2. Open `/tickets` — new row should appear shortly after the message hits the inbox
3. Wait for processing (Ollama) — refresh; status should become `DRAFT_READY` when successful
4. Open ticket detail — read generated draft

More detail: [TESTING_LLM_EMAIL_WORKFLOW.md](./TESTING_LLM_EMAIL_WORKFLOW.md).

---

## Optional: `.env` for local shell (not read by Spring by default)

Copy [`.env.example`](../.env.example) to `.env`, fill secrets, **do not commit** `.env`.

IntelliJ does not load `.env` unless you use a plugin or paste values into Run Configuration. For this project, **Run Configuration env vars are the source of truth** for IDE runs.

Docker Compose can use `.env` for the `backend` service when you run the full stack; for **local** `InboxApplication` in IDE, still set variables in Run Configuration.
