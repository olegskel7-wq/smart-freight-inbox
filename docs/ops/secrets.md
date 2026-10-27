# Secrets (Gate C5)

**Rule:** no password, key or token lives in the repo working tree, in `.env`, in
`docker-compose.yml` or in `docker inspect`. Each secret is one file in `SECRETS_DIR` (outside the
repo, file name = secret name, value without a trailing newline), mounted by compose into
`/run/secrets/` of only the services that need it. `.env` keeps the non-secret config (hosts, flags,
`TELEGRAM_CHAT_ID`, `OFFSITE_S3_BUCKET`) and the `SECRETS_DIR` path. This is the bridge to Vault (4.6):
the services already read files, so a Vault agent can later render the same files.

| Where | `SECRETS_DIR` |
|---|---|
| Fresh clone / CI | unset → `./secrets.dev` (public dev values, in git — never put a real value there) |
| Oleg's dev machine | `C:/Users/olegs/.smartfreight/secrets` |
| Server | `/etc/smartfreight/secrets` (owned by the deploy user, mode `700`; files `644`) |

## The secrets

| File | Used by | Note |
|---|---|---|
| `POSTGRES_PASSWORD` | postgres, postgres-backup, backend (Flyway) | applied at first volume init only |
| `DB_APP_PASSWORD` | backend (app pool + V19 role) | |
| `RABBITMQ_PASSWORD` | rabbitmq, backend | applied at first volume init only |
| `REDIS_PASSWORD` | redis, backend | |
| `KEYCLOAK_ADMIN_PASSWORD` | keycloak (bootstrap admin), `erase-tenant.sh` | bootstrap only — first start of keycloak-db |
| `KEYCLOAK_DB_PASSWORD` | keycloak, keycloak-db, keycloak-backup | applied at first volume init only |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | backend | must match the `smartfreight-admin` client (realm import hardcodes the dev value — Gate C3) |
| `GRAFANA_PASSWORD` | grafana | applied when `grafana_data` is first created |
| `PORT_DB_PASSWORD` | port-db, port-service | applied at first volume init only |
| `PORT_SERVICE_API_KEY` | backend, port-service | |
| `MAILBOX_ENCRYPTION_KEY` | backend | AES master key — **never change once set**; keep a copy outside the server |
| `IMAP_PASSWORD`, `SMTP_PASSWORD` | backend (platform mail account) | may be empty files; SMTP no longer falls back to IMAP — same account ⇒ same value in both |
| `TELEGRAM_BOT_TOKEN`, `HEALTHCHECKS_PING_URL` | alertmanager | Gate B4 |
| `BACKUP_ENCRYPTION_PASSPHRASE`, `OFFSITE_S3_ACCESS_KEY_ID`, `OFFSITE_S3_SECRET_ACCESS_KEY`, `BACKUP_HEALTHCHECKS_PING_URL` | backup-offsite | Gate B1 — keep the passphrase outside the server |

How each service reads them: Spring (backend, port-service) — `spring.config.import:
optional:configtree:/run/secrets/` turns each file into a property of the same name; postgres images
and postgres-backup — `POSTGRES_PASSWORD_FILE`; grafana — `GF_SECURITY_ADMIN_PASSWORD__FILE`; rabbitmq,
keycloak, redis, alertmanager, backup-offsite — `scripts/file-env.sh` exports the files as env vars
inside the container only (redis then moves its password into a root-written config file,
`monitoring/redis/entrypoint.sh`). Healthchecks and scripts that `docker compose exec` into a container
source it first — `. /file-env.sh && …` — since exec sessions do not inherit those env vars.

## New server (once)

```bash
sudo mkdir -p /etc/smartfreight/secrets
sudo chown -R "$USER" /etc/smartfreight && chmod 700 /etc/smartfreight/secrets   # migrate writes its .env backup into /etc/smartfreight
cd /etc/smartfreight/secrets
for n in POSTGRES_PASSWORD DB_APP_PASSWORD RABBITMQ_PASSWORD REDIS_PASSWORD KEYCLOAK_ADMIN_PASSWORD \
         KEYCLOAK_DB_PASSWORD KEYCLOAK_ADMIN_CLIENT_SECRET GRAFANA_PASSWORD PORT_DB_PASSWORD PORT_SERVICE_API_KEY; do
  openssl rand -base64 24 | tr -d '\n' > "$n"
done
openssl rand -base64 32 | tr -d '\n' > MAILBOX_ENCRYPTION_KEY
openssl rand -base64 32 | tr -d '\n' > BACKUP_ENCRYPTION_PASSPHRASE
printf '%s' '<telegram bot token>'   > TELEGRAM_BOT_TOKEN          # docs/ops/alerting.md
printf '%s' '<healthchecks ping url>' > HEALTHCHECKS_PING_URL
printf '%s' '<s3 key id>'            > OFFSITE_S3_ACCESS_KEY_ID    # docs/ops/backup-restore.md
printf '%s' '<s3 secret>'            > OFFSITE_S3_SECRET_ACCESS_KEY
printf '%s' '<backup ping url>'      > BACKUP_HEALTHCHECKS_PING_URL
: > IMAP_PASSWORD; : > SMTP_PASSWORD     # or the platform account's password — in BOTH files (no fallback)
chmod 644 ./*
```

Then in the repo's `.env` on the server: `SECRETS_DIR=/etc/smartfreight/secrets` plus the non-secret
config from `.env.example`, and check — it must print `check-prod-env: OK` before the first `up`:

```bash
sh scripts/check-prod-env.sh .env
```

Generate everything **before the first `docker compose up`**: the database, RabbitMQ and Grafana images
apply their passwords only when their volume is created. Copy `MAILBOX_ENCRYPTION_KEY` and
`BACKUP_ENCRYPTION_PASSPHRASE` into the password manager (+ an offline copy) — without them restored
backups are unusable (`backup-restore.md`). `KEYCLOAK_ADMIN_CLIENT_SECRET`: set the same value on the
`smartfreight-admin` client in the Keycloak admin console (Clients → Credentials).

The deploy (`.github/workflows/deploy.yml`) never writes secrets — none pass through GitHub — it only
runs `check-prod-env.sh`, which refuses: a missing/in-repo/group-readable `SECRETS_DIR` (or one
exported in the shell, which compose would prefer over `.env`), a missing file, a dev default or a
value under 16 characters, a CR in a file, a secret still assigned in `.env` (also `export NAME=`),
an IMAP password without the SMTP one. The secret list itself is `ls secrets.dev` — a new secret is a
new file there plus its compose entries; both scripts pick it up.

## Existing `.env` with secrets → files (one-time)

```bash
sh scripts/migrate-env-to-secrets.sh .env /etc/smartfreight/secrets   # Windows: /c/Users/<you>/.smartfreight/secrets
docker compose up -d
```

Each secret comes from `.env`, else from `secrets.dev` (the value compose used to fall back to, so
volumes keep matching); existing files are kept. The secret lines are removed from `.env`,
`SECRETS_DIR` is appended, and the original `.env` is copied next to the secrets dir
(`env.backup-<timestamp>`, mode 600) — **delete that copy** once the stack is healthy. Values are
never printed. Done on Oleg's dev machine on 27 Oct 2026.

## Rotation

Write the new value without a newline, then restart the consumers:

```bash
printf '%s' "$(openssl rand -base64 24)" > /etc/smartfreight/secrets/REDIS_PASSWORD
docker compose up -d --force-recreate redis backend
```

Secrets marked "first volume init only" must ALSO be changed inside the running service first, e.g.
`ALTER ROLE postgres PASSWORD '…'` / `ALTER ROLE smartfreight_app PASSWORD '…'` in postgres,
`rabbitmqctl change_password smartfreight '…'`, Grafana admin UI, Keycloak admin console. Never rotate
`MAILBOX_ENCRYPTION_KEY` by editing the file — existing mailbox passwords would become undecryptable
(re-encryption procedure: `2026-07-06-A1-secret-rotation.md`).

## IntelliJ runs

There is no `/run/secrets` outside a container (the configtree import is optional), so an IntelliJ
backend still takes secrets from its Run Configuration environment variables or the dev defaults in
`application.yaml`.
