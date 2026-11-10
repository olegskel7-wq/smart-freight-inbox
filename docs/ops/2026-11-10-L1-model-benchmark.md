# L1 — model benchmark: local Ollama vs Claude (10 Nov 2026)

Evidence for ROADMAP "LLM strategy", step L1. Question: how much better is a cloud model than the
local 2024 models on our own mail, and what does it cost per email?

## Method

- **Set:** the golden set — 26 demo emails (18 live-demo + 8 "hard" cases), expected outcomes in
  `scripts/golden-expected.json` (version 2).
- **Real pipeline, no production code changed.** `scripts/eval/ollama-eval-proxy.mjs` speaks Ollama's
  `/api/generate` and answers with another model; the demo tenants' `ollama.base-url` points at it for
  the length of a run. Prompts, rate / knowledge / example context and all routing are the live ones.
  Embeddings stay on the local Ollama.
- **Two scores per run.** Fields (type, urgency, tone, identifier, document handling) are checked
  exactly by `golden-snapshot.ps1 -Score`. Draft text is checked by a judge
  (`scripts/eval/judge-drafts.mjs`, Claude Opus 5.5 — not a model under test) against each scenario's
  must / must-not checklist; a draft passes when every must is met and no must-not is violated.
- **Claude settings:** thinking is always on for these models; effort `low` for the short calls
  (classification, extraction, document type), `medium` for drafts. No sampling parameters.
- **Reproduce:** `scripts/eval/run-eval.sh <label> <short-model> <draft-model>` (Git Bash, demo harness up).

## Results

| | qwen2.5:7b + llama3.1:8b (local, today) | Claude Haiku 5.5 | Claude Sonnet 5.5 |
|---|---|---|---|
| Scenarios with every field right | 17 of 26 | 24 of 26 | 23 of 26 |
| Type | 23 / 26 | 24 / 26 | 25 / 26 |
| Urgency | 20 / 23 | 23 / 23 | 21 / 23 |
| Tone (angry or not) | 19 / 23 | 23 / 23 | 23 / 23 |
| Identifier | 23 / 23 | 23 / 23 | 23 / 23 |
| Drafts passing their checklist | 4 of 23 | 12 of 23 | 15 of 23 |
| Must items met | 20 / 42 | 31 / 42 | 33 / 42 |
| Must-not items violated | 6 / 28 | 2 / 28 | 2 / 28 |
| Median seconds per ticket (26 sent at once) | 141 | 23 | 18 |
| Cost per email | GPU time | $0.0005 | $0.0052 |

A mixed setup — Haiku for the short calls, Sonnet for drafts — costs about $0.0036 per email
(computed from the two call logs, not run separately).

Token profile, the same for every model: about 3 LLM calls per email, ~1,100 input tokens in total.
Output is where the models differ: Haiku ~750 tokens per email, Sonnet ~300 (thinking included; Haiku spends more of it at the
same effort). At 4,000 emails a month per tenant that is about $2 (Haiku), $14 (mixed) or $21 (Sonnet)
per tenant per month — the earlier desk estimate of $0.024 per email for Sonnet was four to five times
too high.

## What the models got wrong

**Local models.** Tone is the weak field: four calm emails were marked angry, which escalates and
auto-assigns them. Drafts are the real gap: 19 of 23 fail. Typical: "we could not find the container in
our system" on emails that never mention a container, invented facts ("includes THC and customs
clearance"), invented DG policy, and missing the customer's actual question.

**Claude.** Field errors are down to one or two judgement calls. Draft failures are mostly shared by
both Claude models and by the local ones — which points at the pipeline, not the model (next section).

## Findings that no model can fix (pipeline and data)

1. **Auto-replies are drafted.** The out-of-office email got a draft from every model. The classifier
   prompt treats any freight-related text as relevant and nothing looks at `Auto-Submitted` /
   "Automatic reply". With auto-send on this is a mail loop. Needs a deterministic filter at ingestion.
2. **The rate context has no unit and no direction.** An LCL request (6 CBM) is quoted the per-container
   FCL rate, and a Felixstowe → Rotterdam booking is offered the Rotterdam → Felixstowe rate: the matcher
   only checks that both place names occur in the email, and the rate line does not say "per 40HC".
   Every model repeated the wrong price. This is the most dangerous finding — a wrong quote in writing.
3. **Demo tracking data is stale.** Every ETA in port-service is from April–May 2026 (the simulator has
   been off since 26 Oct), so every status reply says "the ETA has passed". Same class of problem as the
   expired demo rates fixed earlier today.
4. **Location is often dropped from status replies** (vessel, terminal, road position) although it is in
   the tracking data — worth one line in the drafting prompt.

## Caveats

- 26 emails, one run per model: differences of one or two scenarios are noise. The gaps between local
  and Claude (17 vs 23–24 on fields, 4 vs 12–15 on drafts) are well outside it; the gap between Haiku
  and Sonnet on drafts (12 vs 15) is suggestive, not proven.
- The expected outcomes and checklists were written by hand before the runs; three checklist items
  that assumed "tracking has no ETA" were corrected after the first run showed it has one (version 2),
  and all runs were re-judged on version 2.
- Seconds per ticket include queue wait (the pipeline works two tickets at a time).
- Demo data only: invented companies on `*.example.com`. No customer mail left the machine.

## Conclusion

Cloud models are clearly better on this set, at a cost that is negligible against a £495 plan. Haiku
5.5 alone already fixes classification and tone and triples the draft pass rate for a twentieth of a
cent per email; Sonnet 5.5 writes the better drafts for half a cent. The pipeline issues above cap
every model's draft quality and are worth fixing before choosing between Haiku and Sonnet for drafts.

Newer local models (Qwen3.5 9B, Gemma 4 12B on a current Ollama) are measured separately — see the
ROADMAP L1 row for the status.
