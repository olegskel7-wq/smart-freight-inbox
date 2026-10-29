# 13. Secrets are files, not environment variables

**Status:** accepted · 2026-10

## Context
Secrets kept in `.env` end up in `docker inspect`, process listings, CI variables and backups of the
working tree.

## Decision
One file per secret in `SECRETS_DIR`, outside the repo (on the server `/etc/smartfreight/secrets`,
directory mode 700), mounted per service under `/run/secrets` via compose `secrets:`. Spring reads
them with `spring.config.import: optional:configtree:/run/secrets/`; other images use `*_FILE`
variables or `scripts/file-env.sh`. A fresh clone falls back to the public dev values in
`secrets.dev/`. `scripts/check-prod-env.sh` refuses a deploy with dev values, missing files, or a
secret left in `.env`.

## Consequences
- Nothing secret passes through GitHub or shows up in `docker inspect`.
- `docker compose exec` sessions do not see the secrets as environment variables; scripts read
  `/run/secrets/<name>`.
- Runbook: [`../ops/secrets.md`](../ops/secrets.md). A future Vault setup can render the same files.
