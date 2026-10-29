# 10. Learn from operators and a knowledge base via local RAG, not fine-tuning

**Status:** accepted · 2026-06

## Context
Drafts should get better the longer a client uses the product, and should answer "how / where /
when" questions that have no structured data behind them. Fine-tuning per tenant is heavy, and a
shared fine-tune would leak across tenants.

## Decision
Retrieval-augmented few-shot prompting, with embeddings in Postgres (pgvector, HNSW):
- **Feedback loop:** when the sent text differs enough from the AI draft (normalised Levenshtein
  > 0.1), store a `training_example`; similar past corrections of the same ticket type are injected
  into later drafts.
- **Knowledge base:** operators keep FAQ and policy articles (`knowledge_doc`); the closest ones
  within a distance threshold ground the draft. There is no recency fallback — an unrelated article
  is worse than none.

Embeddings come from the same local Ollama. Retrieval is auxiliary: any failure simply adds nothing.

## Consequences
- Per-tenant isolation comes for free (RLS); corrections never leave the server.
- "AI draft acceptance" (sent unchanged vs edited) is a KPI on `/analytics`.
- Prompt placeholders are substituted trusted context first and untrusted email last, so an email
  cannot inject a placeholder.
