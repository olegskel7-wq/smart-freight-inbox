# 1. Local LLMs via Ollama, two models

**Status:** accepted · 2026-05

## Context
The product promise is that customer email never leaves UK infrastructure (GDPR; NHS, legal and
finance buyers). Hosted LLM APIs are US-operated. Extraction needs precision; replies need fluent prose.

## Decision
Run the models locally in Ollama: **qwen2.5:7b** for classification and extraction (structured,
terse output), **llama3.1:8b** for writing replies, **nomic-embed-text** for embeddings. The endpoint
is a per-tenant setting (`ollama.base-url`), so a client can point at its own GPU server.

## Consequences
- Data residency is a property of the deployment, not a contract clause.
- Small models drift: every LLM output is parsed leniently with fail-safe defaults, and numbers that
  matter (analytics, confidence) are never LLM-generated.
- A GPU (~16 GB VRAM) is part of the hosting cost; usage is metered per tenant
  (`smartfreight_llm_calls_total`).
- Model upgrades are A/B-tested on the golden set (`scripts/demo-mail.ps1`).
