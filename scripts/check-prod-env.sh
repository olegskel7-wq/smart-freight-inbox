#!/bin/sh
# Gate C1 + C5: refuse to deploy with dev credentials, secrets in the working tree or dev-only switches.
#
# docker-compose.yml takes every secret from a file in SECRETS_DIR (default ./secrets.dev = the
# public dev values, so a fresh clone runs with no setup). On a server SECRETS_DIR must point OUTSIDE
# the repo (/etc/smartfreight/secrets — docs/ops/secrets.md) and every secret must be real. This
# script reads the server's .env for the non-secret config and SECRETS_DIR (never sources it — no
# code from it runs), then the secret files. It fails if a secret is missing, equals its dev default
# or is shorter than 16 characters, if a secret is still assigned in .env, or if a dev-only switch is
# on. Values are never printed. Run by .github/workflows/deploy.yml before `docker compose up`; by hand:
#
#   sh scripts/check-prod-env.sh .env
#
# Generate a value: openssl rand -base64 24   (MAILBOX_ENCRYPTION_KEY: openssl rand -base64 32)
#
# NOTE: POSTGRES_PASSWORD / RABBITMQ_PASSWORD / KEYCLOAK_DB_PASSWORD / GRAFANA_PASSWORD only take
# effect on a FRESH volume (the images apply them at first init). Set them before the first `up`, or
# rotate inside the running service. KEYCLOAK_ADMIN_CLIENT_SECRET must also match the
# smartfreight-admin client in Keycloak — the realm import still hardcodes it (rotate in the admin
# console; Gate C3).

ENV_FILE="${1:-.env}"
[ -f "$ENV_FILE" ] || { echo "check-prod-env: $ENV_FILE not found" >&2; exit 1; }
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"

# Last assignment wins, as in docker compose. Drops a CR (a .env prepared on Windows) and one
# pair of surrounding quotes.
value_of() {
    grep -E "^[[:space:]]*$1=" "$ENV_FILE" | tail -n 1 | tr -d '\r' | sed -E "s/^[^=]*=//; s/^[\"'](.*)[\"']\$/\1/"
}

fail=0
problem() { echo "  ✗ $1" >&2; fail=1; }

# --- where the secrets live ------------------------------------------------------------------
SECRETS_DIR="$(value_of SECRETS_DIR)"
if [ -z "$SECRETS_DIR" ]; then
    echo "  ✗ SECRETS_DIR is not set in $ENV_FILE (compose would mount ./secrets.dev — the public dev values)" >&2
    echo "check-prod-env: $ENV_FILE is not fit for a server — fix the items above." >&2
    exit 1
fi
if [ ! -d "$SECRETS_DIR" ]; then
    echo "  ✗ SECRETS_DIR $SECRETS_DIR does not exist (docs/ops/secrets.md)" >&2
    exit 1
fi
secrets_real="$(cd "$SECRETS_DIR" && pwd -P)"
case "$secrets_real/" in
    "$REPO_ROOT"/*) problem "SECRETS_DIR is inside the repo working tree — move it out (e.g. /etc/smartfreight/secrets)" ;;
esac
case "$SECRETS_DIR" in
    /*) ;;
    *) problem "SECRETS_DIR must be an absolute path (a relative one resolves against the repo)" ;;
esac
# Nobody but its owner may list or read the directory (the files themselves are 0444 so that every
# container user can read its bind mount — the directory is what keeps other host users out).
mode="$(stat -c %a "$secrets_real" 2>/dev/null || echo unknown)"
case "$mode" in
    *00) ;;
    *) problem "SECRETS_DIR permissions are $mode — must be 700 (chmod 700 $SECRETS_DIR)" ;;
esac

# Every secret docker-compose.yml mounts (its top-level `secrets:`). Optional ones may be empty files.
ALL_SECRETS="POSTGRES_PASSWORD DB_APP_PASSWORD RABBITMQ_PASSWORD REDIS_PASSWORD KEYCLOAK_ADMIN_PASSWORD
    KEYCLOAK_DB_PASSWORD KEYCLOAK_ADMIN_CLIENT_SECRET GRAFANA_PASSWORD PORT_DB_PASSWORD PORT_SERVICE_API_KEY
    MAILBOX_ENCRYPTION_KEY IMAP_PASSWORD SMTP_PASSWORD TELEGRAM_BOT_TOKEN HEALTHCHECKS_PING_URL
    BACKUP_ENCRYPTION_PASSPHRASE OFFSITE_S3_ACCESS_KEY_ID OFFSITE_S3_SECRET_ACCESS_KEY BACKUP_HEALTHCHECKS_PING_URL"

for name in $ALL_SECRETS; do
    f="$secrets_real/$name"
    if [ ! -f "$f" ]; then
        problem "$name is missing from SECRETS_DIR (compose refuses to start without it; may be empty if optional)"
    elif grep -q "$(printf '\r')" "$f"; then
        problem "$name contains a CR (edited on Windows?) — rewrite it: printf '%s' '<value>' > $name"
    fi
    # A secret must not ALSO sit in .env — that is exactly the cleartext-in-the-working-tree C5 removes.
    grep -qE "^[[:space:]]*$name=" "$ENV_FILE" && problem "$name is assigned in $ENV_FILE — secrets belong in SECRETS_DIR only (delete the line)"
done

# The file's content (missing → empty; already reported above). Runs in $(...) — never calls problem.
secret_of() { tr -d '\r\n' < "$secrets_real/$1" 2>/dev/null; }

# --- secrets: present, not a dev default, long enough ---------------------------------------
# name:dev-default (the value in secrets.dev). The backend repeats this check at startup for the
# secrets it resolves itself (DevCredentialsGuard, Gate C6) — keep the two in sync.
for pair in \
    POSTGRES_PASSWORD:postgres \
    DB_APP_PASSWORD:smartfreight_app \
    RABBITMQ_PASSWORD:smartfreight \
    REDIS_PASSWORD:smartfreight-dev-redis \
    KEYCLOAK_ADMIN_PASSWORD:admin \
    KEYCLOAK_DB_PASSWORD:keycloak \
    KEYCLOAK_ADMIN_CLIENT_SECRET:smartfreight-admin-secret \
    GRAFANA_PASSWORD:admin \
    PORT_DB_PASSWORD:postgres \
    PORT_SERVICE_API_KEY:smartfreight-port-secret \
    MAILBOX_ENCRYPTION_KEY:
do
    name="${pair%%:*}"
    dev_default="${pair#*:}"
    [ -f "$secrets_real/$name" ] || continue
    v="$(secret_of "$name")"
    if [ -z "$v" ]; then
        problem "$name is empty"
    elif [ "$v" = "$dev_default" ]; then
        problem "$name still has the dev default"
    elif [ "${#v}" -lt 16 ]; then
        problem "$name is shorter than 16 characters"
    fi
done

# Gate B1: backups must leave the host, encrypted. Bucket credentials have no dev default, so only
# presence is checked; the passphrase is a secret like any other (and must also live OUTSIDE the server).
[ -z "$(value_of OFFSITE_S3_BUCKET)" ] && problem "OFFSITE_S3_BUCKET is not set in $ENV_FILE (no offsite backup — docs/ops/backup-restore.md)"
for name in OFFSITE_S3_ACCESS_KEY_ID OFFSITE_S3_SECRET_ACCESS_KEY BACKUP_HEALTHCHECKS_PING_URL; do
    [ -z "$(secret_of "$name")" ] && problem "$name is empty (no offsite backup — docs/ops/backup-restore.md)"
done
passphrase="$(secret_of BACKUP_ENCRYPTION_PASSPHRASE)"
[ "${#passphrase}" -lt 16 ] && problem "BACKUP_ENCRYPTION_PASSPHRASE is empty or shorter than 16 characters"
[ -n "$(value_of OFFSITE_LOCAL_DIR)" ] && problem "OFFSITE_LOCAL_DIR is set (drill-only: backups would stay on this host)"

# Gate B4: a server without an alert channel fails silently — both must be configured.
[ -z "$(value_of TELEGRAM_CHAT_ID)" ] && problem "TELEGRAM_CHAT_ID is not set in $ENV_FILE (alerts would reach nobody — docs/ops/alerting.md)"
for name in TELEGRAM_BOT_TOKEN HEALTHCHECKS_PING_URL; do
    [ -z "$(secret_of "$name")" ] && problem "$name is empty (alerts would reach nobody — docs/ops/alerting.md)"
done

# Dev-only switches that must be off on a server.
[ -n "$(value_of USE_SYSTEM_CA_CERTS)" ] && problem "USE_SYSTEM_CA_CERTS is set (dev-only Norton CA trust)"
[ "$(value_of DEMO_MAIL_ENABLED)" = "true" ] && problem "DEMO_MAIL_ENABLED=true (replies would go to Mailpit — Gate B14)"
case ",$(value_of COMPOSE_PROFILES)," in
    *,demo,*) problem "COMPOSE_PROFILES contains demo (GreenMail/Mailpit — Gate B14)" ;;
esac

if [ "$fail" -ne 0 ]; then
    echo "check-prod-env: $ENV_FILE / $SECRETS_DIR are not fit for a server — fix the items above." >&2
    exit 1
fi
echo "check-prod-env: OK"
