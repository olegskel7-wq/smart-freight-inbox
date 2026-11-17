# Architecture Decision Records

One decision per page: the context, what was decided, and what it costs. How the system works as a
whole is described in [`../ARCHITECTURE.md`](../ARCHITECTURE.md). A new significant decision gets a
new file with the next number; a reversed decision is marked *superseded* rather than deleted.

| # | Decision |
|---|---|
| [1](0001-local-llm-two-models.md) | Local LLMs via Ollama, two models |
| [2](0002-imap-idle-dynamic-adapters.md) | IMAP IDLE with adapters built from the database |
| [3](0003-transactional-outbox-rabbitmq.md) | Transactional outbox → RabbitMQ for the pipeline |
| [4](0004-two-layer-multitenancy.md) | Multi-tenancy: Hibernate @TenantId plus Postgres RLS |
| [5](0005-per-tenant-mailboxes-and-llm.md) | Per-tenant mailboxes, SMTP and LLM endpoints |
| [6](0006-human-in-the-loop-auto-send-gate.md) | Human in the loop by default; one auto-send gate |
| [7](0007-keycloak-identity.md) | Keycloak is the only identity store |
| [8](0008-react-spa-jwt.md) | React SPA + stateless JWT resource server |
| [9](0009-proactive-emails.md) | Proactive emails reuse the reactive scaffold |
| [10](0010-rag-feedback-and-knowledge.md) | Learn via local RAG, not fine-tuning |
| [11](0011-nl-query-metric-router.md) | "Ask your inbox" routes to metrics, never writes SQL |
| [12](0012-smart-routing.md) | Urgency and sentiment from the same classifier call |
| [13](0013-secrets-as-files.md) | Secrets are files, not environment variables |

## Template

```markdown
# N. Title

**Status:** proposed | accepted | superseded by N · YYYY-MM

## Context
## Decision
## Consequences
```
