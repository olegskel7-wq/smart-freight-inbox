# Refactoring plan and safety net

Started 1 Nov 2026. Goal: code that is easier to change, with **no change in behaviour**. Every step is
a pure refactoring; features and fixes never ride along in the same branch.

## Rules for every step

1. One branch `refactor/<name>` = one refactoring. IDE refactorings (rename / extract / move) over hand edits.
2. Database migrations and the `/api/*` contract are not touched. If a step needs either, it becomes a
   separate, explicitly approved change.
3. Before touching a class, its behaviour is pinned by tests. Where coverage is thin, characterisation
   tests (they record what the code does today) come first, in their own commit.
4. Before and after the step:
   - `./mvnw -DskipFrontend test` — all green;
   - `npm run build` (and `npm run lint` once it is green, see below);
   - golden set: `scripts/golden-snapshot.ps1 -Capture`, then `-Compare` against the baseline.
5. Before merging: security-review → code-review → simplify, then `merge --no-ff`.

## Golden-set snapshot

`scripts/golden-snapshot.ps1` sends the 18 scenarios of `scripts/demo-mail.ps1` through the live
pipeline (real IMAP, RabbitMQ, Ollama), waits for every ticket to leave the pipeline and records one row
per scenario: status, type, urgency, sentiment, identifier, document handling, mailbox, assignment,
draft present, confidence, audit actions, shipment watch / quote follow-up armed. Snapshots go to
`golden-snapshots/` (git-ignored — they depend on the local stack and model).

```
powershell -ExecutionPolicy Bypass -File scripts\golden-snapshot.ps1 -Capture -Label after-step-x
powershell -ExecutionPolicy Bypass -File scripts\golden-snapshot.ps1 -Compare golden-snapshots\baseline-1.json -Against golden-snapshots\after-step-x.json
```

Ollama samples with its default temperature, so outcomes are not fully deterministic. Two baseline runs
on main `d81a2c4` differed in **1 field out of 18 × 16**: `felixstowe-invoice` urgency 1 ↔ 2. A
difference there is noise; any other difference is a regression until proven otherwise (re-capture once
to rule out noise).

Known classifier quirks the baseline records as-is (not to be "fixed" inside a refactoring):
`felixstowe-general-hazmat` → RFQ (expected GENERAL), `midlands-urgent-damage` → INVOICE_QUERY
(expected GENERAL).

## Baseline (main `d81a2c4`, 1 Nov 2026)

- Backend: **689 tests, 0 failures**. JaCoCo on a clean run: 83 % instructions, 70 % branches
  (CI gate: 60 % instructions).
- Frontend: `npm run build` OK. `npm run lint` **fails with 12 errors**, all
  `react-hooks/set-state-in-effect` (`void load()` inside `useEffect` on 11 pages) — new in
  eslint-plugin-react-hooks 7. CI does not run lint today.
- No frontend tests, no ArchUnit rules, no formatter.
- Local `./mvnw verify` stops at `frontend-maven-plugin` resolution: Norton TLS inspection re-issued its
  CA and the JDK truststore holds the old one, so new Maven artifacts cannot be downloaded
  (`-DskipFrontend test` works from the local cache).

Branch coverage of the classes the plan touches (clean run):

| Class | Branches | Missed |
|---|---|---|
| `KeycloakAdminService` | 45 % | 24 |
| `MailboxImapAdapterManager` | 45 % | 12 |
| `InboxQueryService` | 46 % | 25 |
| `EmailIngestionService` | 50 % | 24 |
| `SettingsApiController` | 50 % | 15 |
| `TicketApiController` | 64 % | 38 |
| `EmailClassificationService` | 69 % | 14 |
| `TicketProcessingService` | 69 % | 21 |
| `AiDraftingService` | 77 % | 15 |

## Found while writing the safety net (behaviour changes — separate branches)

- ✅ Fixed in `46ca462` (branch `fix/inbox-query-locale`): `InboxQueryService` headlines followed the
  JVM default locale (`20,0%` on a ru-RU machine) and built the figures block with `%n` (CRLF on
  Windows). They now use `Locale.ENGLISH` and `\n`; ru-RU tests guard it.

- ✅ CI had never passed: `mvnw` was committed without the executable bit (exit 126 on every run since
  the first commit). Fixed in `93b4441`; the first real run then passed all 737 tests on Linux and
  failed in `frontend-maven-plugin` — Node 20.18 is below vite 8's 20.19, so npm skipped rolldown's
  native binding. `node.version` is now v22.14.0. The Dockerfile's `node:20` resolves to 20.20 and
  builds fine; moving it to `node:22` is optional.
- `npm audit --omit=dev`: 2 findings in `react-router` (1 high), present before this work.
- About 20 `toUpperCase()` / `toLowerCase()` calls take no `Locale`, several of them before an
  enum `valueOf(...)`. Under a Turkish default locale (`i` → `İ`) those parses fail. Not reachable in
  the en_US container; a separate small fix (`Locale.ROOT`) after the classes involved are refactored.
- `smartfreight_ollama_request_seconds{operation="drafting"}` carries a hard-coded `model="llama3.1:8b"`
  tag whatever `ollama.drafting-model` is set to (now in `OllamaDraftClient`; pinned by
  `AiDraftingPromptCharacterizationTest`). Tag from the configured model in a separate small fix.
- `SLA_EXEMPT_STATUSES` omits `AUTO_SENT` and auto-send does not clear `sla_deadline_at`, so a ticket answered
  in time is flagged breached once its deadline passes, and the analytics SLA count (no status filter)
  includes it. Fix in a separate branch.
- `existsInboundReplyFrom` is a plain substring match: a quote to `an@acme.test` counts `jan@acme.test` as
  the reply and drops the follow-up (it fails toward not nudging); `_` and `%` in an address act as wildcards.
  Pinned as a known limitation by `TicketBulkUpdateQueriesIntegrationTest`.
- `@EnableScheduling` has no off switch, so the real schedulers run inside the shared Spring test context.
  `WebhookTenantIsolationTest` and `TicketBulkUpdateQueriesIntegrationTest` date their rows ahead to stay out of
  reach. If another test has to do this, or a scheduler causes a flake, make scheduling switchable for all tests.
- 🔴 `POST /api/settings/autosend` stores `NaN` as the threshold (it parses as a double and survives the clamp), and
  `AutoSendService` then compares `confidence < NaN`, which is never true: the confidence gate is off and every
  allowlisted ticket is emailed unreviewed. Admin-only input. Pinned as-is by the characterisation test; fix in a
  separate branch, on both the save and the read. A `null` element in `types` answers 500; a missing prompt answers
  500 after the other prompt was stored (the writes are separate transactions; the SPA always sends both).
- ✅ CI was red from 3 Nov because the runner moved to Ubuntu 26.04 with Docker 29, not because of this work: the
  previously green `68e369a` failed the same way when re-run. Testcontainers 1.21.3 asked for an API version
  Docker 29 refuses, so every container test failed with "Could not find a valid Docker environment". Local runs
  hid it because `~/.docker-java.properties` on the dev machine pins `api.version`. Fixed by Testcontainers 1.21.4.

## Plan

**Stage 0 — safety net (no production code changes)**
1. ✅ Baseline above + golden-set snapshot script.
2. ✅ ArchUnit rules for what holds today (`src/test/java/com/smartfreight/inbox/ArchitectureTest.java`):
   repositories depend only on entities; nothing outside `controller` depends on it; services do not
   depend on entry points (controller / messaging / integration / scheduler); `tenancy`, `util`,
   `exception` depend on no other application package; entities depend only on entities (+ the live
   event listener); every `@Entity` has a `@TenantId` field except `Tenant`, `AppSetting`, `OutboxEvent`.
   Known exceptions are listed by name and may only shrink: 5 controllers still use repositories
   directly (`AdminController`, `DocumentApiController`, `RateApiController`, `TicketApiController`,
   `TrainingExampleApiController`). Package cycles exist today (service ↔ config, service ↔ webhook,
   service ↔ startup, entity ↔ messaging), so a no-cycles rule waits for the package-by-feature step.
3. Characterisation tests up to ~70 % branch coverage for each class before it is refactored.
   ✅ `TicketProcessingService` 69 → **97 %** branches (`TicketProcessingPipelineTest`: retry loop,
   auto-send after the attempt, optimistic-lock retries, unexpected errors, empty identifier,
   confidence inputs, shipment watch, attachment text, bulk reset) and `InboxQueryService`
   46 → **89 %** (every metric's headline pinned to exact text, the facts sent for phrasing, tenant
   prompt substitution, degradation paths). 734 tests, 87 % / 73 % overall. The remaining classes
   (`TicketApiController`, `AiDraftingService`, `SettingsApiController`, `EmailTicketRepository`) get
   theirs as the first commit of their own refactoring step.
4. ✅ Frontend: vitest 5 + Testing Library (`npm test`); `SettingsPage.test.tsx` (6) stubs `fetch`, so
   the real `useApi` runs and the tests assert method, path, bearer token and JSON body — load,
   render of every section, load error, auto-send save (+ "no types" warning), AI endpoint
   save/clear, rejected save. Lint is green: `react-hooks/set-state-in-effect` was a warning (12, the
   fetch-on-mount pattern on every page — fixed in step 1.3, now an error). CI runs lint + tests in a separate
   `frontend` job on Node 22; deploy waits for it.
5. Optional: Spotless + Prettier as one format-only commit listed in `.git-blame-ignore-revs`.

**Stage 1 — low risk**
1. ✅ Replace the "Phase X / Gate Y" journal comments (≈365 in Java, 45 in the frontend) with what/why
   comments and ADR links. Branch `refactor/1.1-journal-comments`: one commit per package group,
   every changed line is a comment line (checked over `git diff -U0`). Left as is on purpose — they
   are runtime strings, not comments: the `DevCredentialsGuard` startup message ("Gate C1/C5/C6") and
   the `smartfreight_document_requests_total` meter description ("Phase 2.7 pipeline"). "Phase A / B"
   in the proactive schedulers is the algorithm's two passes, not a journal tag. 773 tests green,
   frontend lint/test/build green.
2. ✅ `AiDraftingService` (593 → 130 lines): one `ReplyDraftRequest` record instead of four `generateDraft`
   overloads; prompt templates + substitution + item block in `DraftPrompts`, static fallbacks in
   `DraftFallbacks`, the Ollama call (signature instruction, sign-off strip, breaker, metrics) in the
   `OllamaDraftClient` bean. Branch `refactor/1.2-ai-drafting`; first commit
   `AiDraftingPromptCharacterizationTest` (26) pins every default prompt, the trusted-first
   placeholder order, the append-fallback, truncation, fallbacks and the request/metric shape —
   branch coverage 77 → 96 %, assertions untouched by the refactor commit. 799 tests green; golden set
   identical to `baseline-1` across 18 scenarios (`-Ignore confidence,watch_armed`: both columns changed
   format/meaning in `f598761`, after the baselines were taken — re-capture baselines on the next step).
3. ✅ `SettingsPage.tsx` (795 → 55 lines) → `pages/settings/`: `useSettings` (snapshot, form seeded
   from it, page alert, `save` / `deleteAndReload`), a `SettingsCard` + `FeatureSwitch`
   shell, one component per card (mailboxes with `MailboxFields` + `mailboxForm` helpers and form-state hook, AI endpoint,
   platform SMTP, notifications, auto-send, status update, quote follow-up, smart routing, prompts,
   SMTP how-to). Branch `refactor/1.3-settings-page`; first commit adds 19 characterisation tests
   (25 in all) for every card — requests, messages, in-form mailbox errors, confirm + reload on
   delete, status badges, the missing-key guard, and the re-seed of every card from the snapshot a
   save returns (verified to fail when the re-seed is broken). Tests untouched by the refactor commit.
   ✅ Fetch-on-mount (branch `refactor/1.3-fetch-on-mount`): ten pages and `useSettings` load through one
   `usePageData` hook (`api/usePageData.ts`) — state is set only from the promise callbacks; `error`
   and `loading` belong to the current `load`, so a URL filter change starts clean without a set-state
   in the effect; `setError` shares the slot for page-level action errors, `reload()` serves live
   updates and post-mutation reloads. `react-hooks/set-state-in-effect` is an error again, lint runs
   at `--max-warnings 0`. One `util/errors.ts` `errorMessage` replaces 30 inline copies. First commit
   `pageLoading.test.tsx` (27) pins every page's request, Loading…, load error, filter refetch, live
   and post-mutation reload; the review added 2 (load error dropped on a tab change; a failed-send
   error survives a silent token renewal — the ticket page keeps action errors apart from the load's).
   Found there and fixed in its own branch `fix/token-renewal-and-stale-loads` (behaviour changes, not
   refactoring): a silent token renewal rebuilt `useApi`, re-ran every page load and re-seeded the
   ticket draft / settings form, wiping unsaved typing — `useApi` is now stable and reads the auth
   context through a ref; an older load answering after a newer one overwrote the data —
   `usePageData` drops it.
   ✅ `TicketsListPage.tsx` (369 → ~180 lines) → `pages/tickets/`: `listQuery.ts` (queue tabs, sorts, URL →
   query → `/api/tickets` request, active tab, title), `SummaryChips`, `TicketFilters`, `TicketRow`, `Pager`.
   Branch `refactor/1.3-tickets-list-page`; first commit `TicketsListPage.test.tsx` (42) pins URL → request
   and title for 17 URLs, tab links, chips, filter and sort changes (the address bar they leave, under a
   real `BrowserRouter`), paging, row rendering and grouping, the queue handed to the ticket page, Claim /
   Release bodies and Reprocess; five mutations of the page each fail it. The load keeps its per-field
   deps, so it reloads exactly when it did before. Found there and fixed in its own branch
   `fix/tickets-url-parsing` (behaviour changes): `?page=abc` was sent as `page=NaN`; `?view=constructor` /
   `?status=toString` resolved the title through `Object.prototype` — label maps are now read by own key
   (`ownLabel` in `util/labels.ts`).
   ✅ `WebhooksPage.tsx` (371 → ~170 lines) → `pages/webhooks/`: `WebhookForm` (its own form state; the
   page's `create` answers whether the webhook was created, so a rejected create keeps what was typed),
   `SigningSecretAlert`, `SubscriptionRow`, `DeliveryLog`, `WebhooksHowToCard`; the page keeps the
   message, the one-time secret and the single open log. Branch `refactor/1.3-webhooks-page`; first commit
   `WebhooksPage.test.tsx` (25) pins the table and status badges, the create body (all events / ticked
   types in order, blanks as null), the secret shown once + Copy + form reset, the rejected create, Test /
   Enable / Disable / Delete bodies and messages, and the delivery log (cells, badges, one open at a time,
   reload on reopen, empty and failed loads); 12 mutations of the page each fail it. Found there and fixed
   in its own branch `fix/webhooks-log-and-create` (behaviour changes): switching logs showed the previous
   log's rows until the new load answered, and an out-of-order answer could leave them there — the
   `DeliveryLog` row now loads its own rows through `usePageData` and is mounted only while open (Loading…
   first); Create was not disabled in flight, so a double click made two webhooks and the first secret was
   never seen; an open log was not refreshed after Test — a queued test bumps that webhook's refresh key.
   +12 tests. Stage 1.3 is complete.

**Stage 2 — pipeline core, medium risk**
1. ✅ `TicketProcessingService.processAttempt` (165 → ~60 lines; 23 fields → 10, constructor 18 → 7
   params) → five package-private `@Component` steps in `service`: `TicketClassificationStep` (relevance
   guard, verdict, SLA window, escalation routing, `booking.confirmed`), `IdentifierLookupStep`
   (extraction + backend lookup), `DraftContextAssembler` (rates, memoised enquiry embedding, few-shot
   examples, knowledge base; Spring `SingletonSupplier` replaces the hand-rolled memoiser), `ReplyDraftStep`
   (document-request path or generic draft, AI original draft, confidence score, document counters),
   `DraftRoutingStep` (shipment watch, auto-send decision, Gmail draft). The steps set ticket
   fields and record their side effects (audit, webhook, shipment watch, Gmail draft), and carry no
   `@Transactional`: the orchestrator keeps every status transition, every ticket save in its old order,
   `REQUIRES_NEW`, the retry loop and `markRetryLater` / `markFailed`; open-breaker exceptions pass through the steps untouched. Branch `refactor/2.1-processing-steps`; first commit
   pins the null rate-context guard. Test bodies untouched — only the two `setUp`s wire the real steps
   over the same mocks. Branches 73/74 (the miss is unreachable: no identifier ⇒ no lookup ⇒ no item);
   764 tests green; golden set identical to `baseline-2.1-main-68e369a` across 18 scenarios (after `/simplify`:
   only the known `felixstowe-invoice` urgency noise). Two log
   lines (document attach/escalate, auto-assign) now log under their step's class.
2. ✅ `TicketApiController` (478 → ~230 lines, 9 → 3 dependencies) split by sub-resource: `TicketApiController`
   keeps the list, the summary strip, detail, close and delete; `TicketReplyController` takes the draft (save /
   close / send with rate limit, SMTP, audit, quote follow-up, feedback capture, reply/quote webhooks) and
   `attach-document`; `TicketAssignmentController` takes claim / hand-over / release with the admin check; the
   package-private `TicketDetailAssembler` loads a ticket (404 when missing or foreign) and builds the detail
   DTO, and resolves assignee e-mails for both the list and the detail. Paths, methods, bodies, status codes and
   `@Transactional` are unchanged (there is no `@PreAuthorize`: `/api/**` → `authenticated()` plus the in-code
   admin check). Branch `refactor/2.2-ticket-api-controller`; first commit
   `TicketApiControllerCharacterizationTest` (62) — branches 64 → 98 %; test bodies untouched afterwards, the
   wiring moved into `TicketApiMockMvcSupport`; `CrossTenantApiAccessTest` calls the moved endpoints on their new
   controllers with the same assertions. ArchitectureTest's repository-user list names the four `Ticket*`
   classes (same debt, spread — a ticket service is the real fix). 826 tests green; golden set identical to
   `baseline-2.2-main-4e57368` across 18 scenarios (it exercises the pipeline, not this API — the API is pinned
   by the MockMvc tests and an unauthenticated 401 check of all 9 endpoints). Log lines of the moved code now
   log under their new class. Left for later: the detail loads `TenantDocument` with its file bytes to build
   `DocumentRefDto` (a projection would skip them); `isAdmin` re-reads the `roles` claim instead of the
   authorities `KeycloakJwtRoleConverter` builds.
3. ✅ `InboxQueryService` (392 → 160 lines) split along its three steps: package-private
   `InboxMetricResolver` (`@Component` over `AnalyticsService` — every metric's headline and figures
   block, `busiestOpenStage`, the English-locale `fmt`, the `QueryIntent` record it consumes),
   `InboxQueryOllamaClient` (`@Component` — the 60 s generate call on the tenant's endpoint and breaker,
   `recordUsage("inbox-query", …)`, every failure → empty) and `InboxQueryPrompts` (guidance, the two V25
   fallback templates, their substitution in the old order). The service keeps `ask`, `route`,
   `parseIntent`, `phrase`, the `smartfreight_inbox_queries_total` counter and
   `@Transactional(readOnly = true)`. Branch `refactor/2.3-inbox-query-service`; first commit pins the null
   question and a type breakdown without unclassified mail — branches 42/47 → 44/47. Test bodies untouched
   afterwards: `setUp` wires the real resolver and client, one import follows `QueryIntent`. `/simplify`
   dropped the two dead `busiest == null` checks (`busiestOpenStage` never returns null) and kept the six
   open stages in one list; the one branch left uncovered is `UNKNOWN` in the resolver (unreachable — `ask`
   answers it first). 828 tests green; golden set identical to `baseline-2.3-main-0085b9e` across 18
   scenarios (it exercises the pipeline, not this service — the headlines are pinned to exact text by
   `InboxQueryServiceTest`). Both Ollama log lines now log under `InboxQueryOllamaClient`. Left for later
   (behaviour changes): `ask` holds its read-only transaction across both Ollama calls (narrow it to
   `summarise`); `answer()` substitutes the untrusted question before the trusted `{facts}`, unlike the
   drafting prompts' trusted-first order.
4. ✅ `EmailTicketRepository`: the planned regrouping was dropped. 258 of its 414 lines are Javadoc and it
   holds no logic, so moving declarations around would change nothing a reader feels. The real gap was
   that the SQL ran unchecked: the callers are tested over a mocked repository. Branch
   `refactor/2.4-repository-safety-net` adds `TicketBulkUpdateQueriesIntegrationTest` (real Postgres, RLS
   on): the four bulk updates and `existsInboundReplyFrom` — rows touched, columns written, strict cutoffs,
   soft-deleted rows, tenant isolation, the system context. The
   JaCoCo gate goes from 60 % instructions to 85 % instructions / 70 % branches.
5. ✅ `SettingsApiController` (346 → 193 lines, 5 → 2 dependencies) split like step 2: it keeps the snapshot GET and
   the settings saves; `MailboxSettingsController` takes add / edit / toggle / delete under the same
   `/api/settings/mailboxes` paths; the package-private `SettingsSnapshotAssembler` builds the `SettingsResponse`
   both answer with. Paths, bodies, status codes and messages are unchanged; `/api/settings/**` stays ADMIN in
   `SecurityConfig`. Branch `refactor/2.5-settings-api-controller`; first commit
   `SettingsApiControllerCharacterizationTest` (33 methods, 49 cases) — branches 50 → 100 %, eight mutations each
   fail it; test bodies untouched afterwards, only the two `setUp`s. The class Javadoc no longer points at the
   deleted Thymeleaf controller, and the milestones Javadoc no longer claims an upper-casing the code never did.

**Stage 3 — only on a separate decision**
1. Remove the binary (pre-1.4) classifier mode — a behaviour change; first confirm no tenant has
   `feature.ticket-type` off. The other `feature.*` flags are per-tenant settings, not dead code.
2. Package-by-feature. Decide the fate of `feature/m365-oauth2` first (it touches mailbox code and
   holds V38).
