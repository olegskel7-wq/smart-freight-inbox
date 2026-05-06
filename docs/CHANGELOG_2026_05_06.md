# Changelog — 06 May 2026

## Overview

Four areas of work: security hardening, email relevance filtering, Gmail Drafts integration, and UI improvements.

---

## 1. Security & Configuration Fixes

### CSRF disabled
**File:** `src/main/java/com/smartfreight/inbox/config/SecurityConfig.java`

Added `.csrf(csrf -> csrf.disable())`. The web UI uses session-based form login with Thymeleaf — CSRF tokens would require template changes throughout. `/admin/**` endpoints are now protected by full authentication (not just `permitAll`), with the existing `isLocalhost()` check as a secondary safeguard.

### Forward headers strategy
**File:** `src/main/resources/application.yaml`

Added `server.forward-headers-strategy: framework` to correctly resolve `X-Forwarded-*` headers when behind a reverse proxy.

### BCrypt password fix
**File:** `src/main/resources/db/migration/V6__fix_demo_user_password.sql`

The hash in `V3` was manually written and did not match `demo123`. Migration `V6` replaces it with a hash verified by `BCryptPasswordEncoder.matches("demo123", hash) == true`.

Demo credentials: `operator@smartfreight.com` / `demo123`

### HTTP client unification
**File:** `src/main/java/com/smartfreight/inbox/actuator/OllamaHealthIndicator.java`

Replaced `RestTemplate` with the injected `WebClient` (`ollamaWebClient`) to be consistent with the rest of the project.

### GlobalExceptionHandler restored
**File:** `src/main/java/com/smartfreight/inbox/exception/GlobalExceptionHandler.java`

Re-added `handleContainerNotFound()` (`ContainerNotFoundException → 404`). The method had been removed as "dead code" but a test was relying on it and `ContainerNotFoundException` may be thrown in future logic paths.

---

## 2. Email Relevance Filter (`EmailClassificationService`)

**New file:** `src/main/java/com/smartfreight/inbox/service/EmailClassificationService.java`

Before creating a ticket, the incoming email is classified by Qwen 2.5 via a single-shot prompt:

```
Reply with exactly one word: RELEVANT or IRRELEVANT.
```

- Irrelevant emails (marketing, personal, etc.) are **dropped silently — no DB record is created**.
- Classification happens **before the first DB query** inside `@Transactional`, so no JDBC connection is held during the LLM call (Spring lazy connection acquisition).
- **Fail-open**: if Ollama is unreachable, the email is treated as relevant.

**Modified:** `src/main/java/com/smartfreight/inbox/integration/EmailIngestionService.java`

Processing order changed to: copy → check Message-ID → extract body → classify → dedup check → save.

**Key bug fixed in the same file:** The `Message-ID` header check was moved to **before** `writeTo()`. Jakarta Mail's `saveChanges()` (called internally by `writeTo`) auto-generates a `Message-ID` if one is missing, making a post-serialisation check unreliable.

---

## 3. Gmail Drafts Integration (`GmailDraftService`)

**New file:** `src/main/java/com/smartfreight/inbox/service/GmailDraftService.java`

After Llama 3.1 generates a draft reply, it is saved directly to the operator's Gmail **Drafts** folder via IMAP APPEND. No Gmail API or OAuth required — uses the same IMAP credentials already configured in `spring.mail.*`.

The draft message is pre-addressed:
- `To`: original customer email
- `Subject`: `Re: <original subject>`
- `In-Reply-To` / `References`: original Message-ID (groups into the customer's conversation thread)

**Folder discovery order:**
1. `[Gmail]/Drafts`
2. `[Google Mail]/Drafts`
3. `Drafts`
4. Any folder whose full name contains "draft" (language-independent fallback)

**Toggle:** `notification.draft-enabled: ${DRAFT_NOTIFY:true}` in `application.yaml`. Set `DRAFT_NOTIFY=false` to disable without rebuilding.

**Also created (kept but not in main pipeline):** `src/main/java/com/smartfreight/inbox/service/DraftNotificationService.java`

Sends a notification email to the operator's inbox (`Reply-To` pointing at the customer). Replaced in the pipeline by `GmailDraftService` due to UX limitations (operator had to copy draft text manually). Kept as a utility bean; the `X-SmartFreight-Draft: true` header it adds is still checked in `EmailIngestionService` to prevent re-processing loops.

**Modified:** `src/main/java/com/smartfreight/inbox/service/TicketProcessingService.java`

After step 3/3 (`DRAFT_READY` saved), calls `gmailDraftService.saveDraft(ticket)`. Non-blocking — failure is logged as `WARN`, ticket status is unaffected.

---

## 4. Delete Tickets (UI)

**Modified:** `src/main/java/com/smartfreight/inbox/controller/TicketViewController.java`

New endpoint `POST /tickets/{id}/delete` — triggers soft delete via `@SQLDelete` (`UPDATE … SET deleted = true`). Record remains in the database for audit purposes but disappears from all UI queries (filtered by `@SQLRestriction(deleted = false)`).

**Modified:** `src/main/resources/templates/tickets.html`

- Delete button added per row (next to "View"), with `confirm()` dialog.
- Flash success message shown after deletion.
- Bootstrap JS bundle added (required for dismissible alert).

**Modified:** `src/main/resources/templates/ticket-detail.html`

- Delete button added to the Actions row at the bottom of the detail page, aligned right via `ms-auto`.

---

## 5. Test Fixes

**Modified:** `src/test/java/com/smartfreight/inbox/integration/EmailIngestionServiceTest.java`

- Constructor updated to include `EmailClassificationService` mock.
- `@MockitoSettings(strictness = Strictness.LENIENT)` added to suppress false-positive `PotentialStubbingProblem` with conditional stubs.
- Default stub `isFreightInquiry(any(), any()) → true` added to `@BeforeEach`.
- **New test:** `processIncomingEmail_irrelevantEmail_skipsWithoutAnyDbInteraction` — verifies that when classifier returns `false`, neither `save()` nor `findByMessageId()` are called.

All 40 tests pass: `BUILD SUCCESS`.

---

## Files Changed

| File | Change |
|------|--------|
| `config/SecurityConfig.java` | CSRF disabled, `/admin/**` requires auth |
| `actuator/OllamaHealthIndicator.java` | RestTemplate → WebClient |
| `exception/GlobalExceptionHandler.java` | Restored `handleContainerNotFound` |
| `integration/EmailIngestionService.java` | Message-ID check before writeTo, classification step, anti-loop header check |
| `service/EmailClassificationService.java` | **New** — LLM email relevance filter |
| `service/DraftNotificationService.java` | **New** — SMTP notification to operator (not active in pipeline) |
| `service/GmailDraftService.java` | **New** — IMAP APPEND to Gmail Drafts |
| `service/TicketProcessingService.java` | Calls GmailDraftService after DRAFT_READY |
| `controller/TicketViewController.java` | DELETE endpoint added |
| `templates/tickets.html` | Delete button, flash message, Bootstrap JS |
| `templates/ticket-detail.html` | Delete button in actions row |
| `resources/application.yaml` | Forward headers, notification flag |
| `db/migration/V6__fix_demo_user_password.sql` | **New** — correct BCrypt hash for demo123 |
| `integration/EmailIngestionServiceTest.java` | Mock fix, lenient mode, new test |
| `README.md` | Removed JWT references, updated security section |
| `DEFENSE_GUIDE.md` | Corrected Spring Boot version (4.0.5) |
