# Backup & restore (Gates B1 / B2)

**What is backed up:** `postgres-backup` (DB `smartfreight` — tickets, tenants, mailboxes, settings) and
`keycloak-backup` (DB `keycloak` — operators, roles, `tenant_id`) take a compressed `pg_dump` daily
into the `postgres_backups` volume (7 daily / 4 weekly / 6 monthly). `backup-offsite` encrypts every
dump **on this host** (rclone crypt) and copies it to an S3 bucket every hour; the provider only ever
holds ciphertext. Not backed up on purpose: Ollama models (re-pulled), Prometheus/Grafana data,
`port-db` (simulated TMS).

## Setup (once per server)

1. **Bucket in the UK:** AWS S3, region **eu-west-2 (London)**, block all public access. (Any S3-compatible
   provider works: set `OFFSITE_S3_PROVIDER` / `OFFSITE_S3_ENDPOINT`.)
2. **IAM user just for backups**, policy limited to that bucket: `s3:PutObject`, `s3:GetObject`,
   `s3:ListBucket`, `s3:DeleteObject` (the last one is for 200-day retention). Create an access key.
3. **Passphrase:** `openssl rand -base64 32`.
4. **Heartbeat:** a healthchecks.io check, period **1 h**, grace **3 h** → ping URL.
5. `.env` on the server: `OFFSITE_S3_BUCKET=<bucket>`. The rest are secrets — files in `SECRETS_DIR`
   (Gate C5, [`secrets.md`](secrets.md)), written without a newline:
   ```
   printf '%s' '<key id>'     > "$SECRETS_DIR/OFFSITE_S3_ACCESS_KEY_ID"
   printf '%s' '<secret>'     > "$SECRETS_DIR/OFFSITE_S3_SECRET_ACCESS_KEY"
   printf '%s' '<passphrase>' > "$SECRETS_DIR/BACKUP_ENCRYPTION_PASSPHRASE"
   printf '%s' '<ping url>'   > "$SECRETS_DIR/BACKUP_HEALTHCHECKS_PING_URL"
   docker compose up -d --force-recreate backup-offsite
   ```
   `scripts/check-prod-env.sh` refuses a server without them.

## Key custody — read this

Keep **two secrets outside the server** (password manager + one offline copy), or a lost host means
backups you cannot use:

| Secret | Without it |
|---|---|
| `BACKUP_ENCRYPTION_PASSPHRASE` | every offsite dump is unreadable ciphertext |
| `MAILBOX_ENCRYPTION_KEY` | the restored DB works, but every tenant mailbox password must be re-entered on /settings |

## Prove it: restore test (monthly, and after any change to backups)

```
bash scripts/restore-test.sh offsite docs/ops/<date>-B2-restore-test.md
```

Pulls the newest dumps back **from the bucket**, decrypts them, restores both into a throwaway
container and checks row counts against the live DB, Flyway, pgvector, RLS under the app role and
Keycloak. `local` instead of `offsite` tests the on-host copies. Exit 0 = restore proven.

## Real disaster: rebuild on a new host

1. New VPS, clone the repo, put the **same** `.env` and `SECRETS_DIR` files back (from the password manager — [`secrets.md`](secrets.md)).
2. `docker compose up -d postgres keycloak-db` (empty volumes).
3. Pull and decrypt the newest dumps:
   ```
   docker compose run --rm -T --entrypoint /bin/sh backup-offsite -c \
     '. /file-env.sh; . /etc/offsite/offsite-env.sh; rclone lsf vault:daily/ | sort | tail -n 2'
   docker compose run --rm -T --entrypoint /bin/sh backup-offsite -c \
     '. /file-env.sh; . /etc/offsite/offsite-env.sh; rclone cat vault:daily/smartfreight-<date>.sql.gz' > smartfreight.sql.gz
   ```
   (same for `keycloak-<date>.sql.gz`).
4. `pg_dump` carries no roles — create the app role first, with the password from `$SECRETS_DIR/DB_APP_PASSWORD`:
   ```
   docker compose exec -T postgres psql -U postgres -c \
     "CREATE ROLE smartfreight_app LOGIN PASSWORD '<DB_APP_PASSWORD>' NOSUPERUSER NOBYPASSRLS"
   gunzip -c smartfreight.sql.gz | docker compose exec -T postgres psql -U postgres -d smartfreight
   gunzip -c keycloak.sql.gz     | docker compose exec -T keycloak-db psql -U <KEYCLOAK_DB_USER> -d keycloak
   ```
5. `docker compose up -d` — Flyway validates the restored history and the app starts. Data written
   after the dump is lost (RPO ≤ 24 h); emails received meanwhile are re-ingested by the 48 h mailbox
   catch-up (Gate B5), as long as they are still in the inbox.
