#!/bin/sh
# Gate C5: one-time move of the secrets out of a .env into SECRETS_DIR (one file per secret).
#
#   sh scripts/migrate-env-to-secrets.sh .env /etc/smartfreight/secrets
#
# For every secret docker-compose.yml mounts: an existing file in the target dir is kept as is;
# otherwise the value comes from .env, and failing that from secrets.dev (the dev default — the same
# value compose used to fall back to, so a running stack keeps its passwords). SMTP_PASSWORD falls
# back to IMAP_PASSWORD, as compose did. Then the secret lines are removed from .env (a copy of the
# original goes NEXT TO the secrets dir, mode 600 — delete it once the stack is verified) and
# SECRETS_DIR is set there. Values are never printed. Re-running is safe (existing files are kept).
set -eu

ENV_FILE="${1:?usage: $0 <.env> <secrets dir outside the repo>}"
DIR="${2:?usage: $0 <.env> <secrets dir outside the repo>}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
[ -f "$ENV_FILE" ] || { echo "migrate: $ENV_FILE not found" >&2; exit 1; }

mkdir -p "$DIR"
chmod 700 "$DIR"
DIR="$(cd "$DIR" && pwd -P)"
case "$DIR/" in
    "$REPO_ROOT"/*)
        rmdir "$DIR" 2>/dev/null || true   # only if we just created it empty
        echo "migrate: $DIR is inside the repo — pick a directory outside it" >&2; exit 1 ;;
esac

# Same parsing as check-prod-env.sh: last assignment wins, CR and one pair of quotes dropped.
value_of() {
    grep -E "^[[:space:]]*$1=" "$ENV_FILE" | tail -n 1 | tr -d '\r' | sed -E "s/^[^=]*=//; s/^[\"'](.*)[\"']\$/\1/"
}

SECRETS="POSTGRES_PASSWORD DB_APP_PASSWORD RABBITMQ_PASSWORD REDIS_PASSWORD KEYCLOAK_ADMIN_PASSWORD
    KEYCLOAK_DB_PASSWORD KEYCLOAK_ADMIN_CLIENT_SECRET GRAFANA_PASSWORD PORT_DB_PASSWORD PORT_SERVICE_API_KEY
    MAILBOX_ENCRYPTION_KEY IMAP_PASSWORD SMTP_PASSWORD TELEGRAM_BOT_TOKEN HEALTHCHECKS_PING_URL
    BACKUP_ENCRYPTION_PASSPHRASE OFFSITE_S3_ACCESS_KEY_ID OFFSITE_S3_SECRET_ACCESS_KEY BACKUP_HEALTHCHECKS_PING_URL"

for name in $SECRETS; do
    target="$DIR/$name"
    if [ -f "$target" ]; then
        echo "  $name: kept (file exists)"
        continue
    fi
    v="$(value_of "$name")"
    source="from $ENV_FILE"
    if [ -z "$v" ] && [ "$name" = SMTP_PASSWORD ]; then
        v="$(value_of IMAP_PASSWORD)"
        source="from $ENV_FILE (IMAP_PASSWORD)"
    fi
    if [ -z "$v" ]; then
        v="$(cat "$REPO_ROOT/secrets.dev/$name")"
        source="dev default"
    fi
    # 644 inside a 700 dir: every container user can read its bind mount, no other host user can.
    (umask 077; printf '%s' "$v" > "$target")
    chmod 644 "$target"
    echo "  $name: $source"
done

backup="$(dirname "$DIR")/env.backup-$(date +%Y%m%d-%H%M%S)"
(umask 077; cp "$ENV_FILE" "$backup")

pattern="^[[:space:]]*($(echo $SECRETS | tr ' ' '|'))="
tmp="$ENV_FILE.tmp.$$"
grep -vE "$pattern" "$ENV_FILE" | grep -vE '^[[:space:]]*SECRETS_DIR=' > "$tmp" || true
# Docker Desktop on Windows wants C:/... rather than Git Bash's /c/...
env_dir="$DIR"
command -v cygpath >/dev/null 2>&1 && env_dir="$(cygpath -m "$DIR")"
printf '\n# Gate C5: secrets live here, one file each (docs/ops/secrets.md) — never in this file.\nSECRETS_DIR=%s\n' "$env_dir" >> "$tmp"
mv "$tmp" "$ENV_FILE"

echo "migrate: secrets written to $DIR; secret lines removed from $ENV_FILE, SECRETS_DIR set."
echo "migrate: original .env copied to $backup — delete it once the stack runs on the new files."
