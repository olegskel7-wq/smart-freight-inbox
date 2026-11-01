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
on main `d81a2c4` differed in **1 field out of 18 × 15**: `felixstowe-invoice` urgency 1 ↔ 2. A
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

## Plan

**Stage 0 — safety net (no production code changes)**
1. ✅ Baseline above + golden-set snapshot script.
2. ArchUnit rules for what holds today: controllers do not use repositories directly, tenancy rules,
   no package cycles.
3. Characterisation tests up to ~70 % branch coverage for each class before it is refactored — first
   `TicketProcessingService.processAttempt` (every outcome: irrelevant, KB-grounded, rate-quoted,
   escalated, Ollama down → retry, breaker open) and `InboxQueryService`.
4. Frontend: vitest + Testing Library smoke tests for `SettingsPage`; make lint green and run it in CI.
5. Optional: Spotless + Prettier as one format-only commit listed in `.git-blame-ignore-revs`.

**Stage 1 — low risk**
1. Replace the "Phase X / Gate Y" journal comments (≈365 in Java, 45 in the frontend) with what/why
   comments and ADR links.
2. `AiDraftingService`: one `DraftRequest` instead of four `generateDraft` overloads; the Ollama call
   and fallbacks, and the prompt templates, in their own classes.
3. `SettingsPage.tsx` (795 lines) → one component per section + hooks; then `TicketsListPage`,
   `WebhooksPage`.

**Stage 2 — pipeline core, medium risk**
1. `TicketProcessingService.processAttempt` (~174 lines, 23 dependencies) → steps (classify, extract,
   context assembly, draft, route) with the service as the orchestrator. Transaction boundaries and
   retry semantics (`markRetryLater` / `markFailed`) must stay identical.
2. `TicketApiController` → split by sub-resource; URLs and `@PreAuthorize` unchanged.
3. `InboxQueryService`, then `EmailTicketRepository` (group queries, SQL unchanged).

**Stage 3 — only on a separate decision**
1. Remove the binary (pre-1.4) classifier mode — a behaviour change; first confirm no tenant has
   `feature.ticket-type` off. The other `feature.*` flags are per-tenant settings, not dead code.
2. Package-by-feature. Decide the fate of `feature/m365-oauth2` first (it touches mailbox code and
   holds V38).
