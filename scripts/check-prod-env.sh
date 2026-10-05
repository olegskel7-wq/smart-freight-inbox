#!/bin/sh
# Gate C1: refuse to deploy with dev credentials or dev-only switches.
#
# docker-compose.yml carries `:-` DEV defaults (postgres/postgres, admin/admin, ...) so a fresh
# clone runs with no .env. On a server every one of them must be overridden. This script reads the
# server's .env (never sources it — no code from it runs) and fails if a secret is missing, equals
# its dev default or is shorter than 16 characters, or if a dev-only switch is on. Values are never
# printed. Run by .github/workflows/deploy.yml before `docker compose up`; by hand:
#
#   sh scripts/check-prod-env.sh .env
#
# Generate a value: openssl rand -base64 24   (MAILBOX_ENCRYPTION_KEY: openssl rand -base64 32)
#
# NOTE: POSTGRES_PASSWORD / RABBITMQ_PASSWORD / KEYCLOAK_DB_PASSWORD only take effect on a FRESH
# volume (the images apply them at first init). Set them before the first `up`, or rotate inside
# the running service. KEYCLOAK_ADMIN_CLIENT_SECRET must also match the smartfreight-admin client
# in Keycloak — the realm import still hardcodes it (rotate in the admin console; Gate C3).

ENV_FILE="${1:-.env}"
[ -f "$ENV_FILE" ] || { echo "check-prod-env: $ENV_FILE not found" >&2; exit 1; }

# Last assignment wins, as in docker compose. Drops a CR (a .env prepared on Windows) and one
# pair of surrounding quotes.
value_of() {
    grep -E "^[[:space:]]*$1=" "$ENV_FILE" | tail -n 1 | tr -d '\r' | sed -E "s/^[^=]*=//; s/^[\"'](.*)[\"']\$/\1/"
}

fail=0
problem() { echo "  ✗ $1" >&2; fail=1; }

# name:dev-default (the value docker-compose.yml falls back to)
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
    v="$(value_of "$name")"
    if [ -z "$v" ]; then
        problem "$name is not set (compose would use its dev default)"
    elif [ "$v" = "$dev_default" ]; then
        problem "$name still has the dev default"
    elif [ "${#v}" -lt 16 ]; then
        problem "$name is shorter than 16 characters"
    fi
done

# Gate B1: backups must leave the host, encrypted. Bucket credentials have no dev default, so only
# presence is checked; the passphrase is a secret like any other (and must also live OUTSIDE the server).
for name in OFFSITE_S3_BUCKET OFFSITE_S3_ACCESS_KEY_ID OFFSITE_S3_SECRET_ACCESS_KEY BACKUP_HEALTHCHECKS_PING_URL; do
    [ -z "$(value_of "$name")" ] && problem "$name is not set (no offsite backup — docs/ops/backup-restore.md)"
done
passphrase="$(value_of BACKUP_ENCRYPTION_PASSPHRASE)"
if [ "${#passphrase}" -lt 16 ]; then
    problem "BACKUP_ENCRYPTION_PASSPHRASE is missing or shorter than 16 characters"
fi
[ -n "$(value_of OFFSITE_LOCAL_DIR)" ] && problem "OFFSITE_LOCAL_DIR is set (drill-only: backups would stay on this host)"

# Gate B4: a server without an alert channel fails silently — both must be configured.
for name in TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID HEALTHCHECKS_PING_URL; do
    [ -z "$(value_of "$name")" ] && problem "$name is not set (alerts would reach nobody — docs/ops/alerting.md)"
done

# Dev-only switches that must be off on a server.
[ -n "$(value_of USE_SYSTEM_CA_CERTS)" ] && problem "USE_SYSTEM_CA_CERTS is set (dev-only Norton CA trust)"
[ "$(value_of DEMO_MAIL_ENABLED)" = "true" ] && problem "DEMO_MAIL_ENABLED=true (replies would go to Mailpit — Gate B14)"
case ",$(value_of COMPOSE_PROFILES)," in
    *,demo,*) problem "COMPOSE_PROFILES contains demo (GreenMail/Mailpit — Gate B14)" ;;
esac

if [ "$fail" -ne 0 ]; then
    echo "check-prod-env: $ENV_FILE is not fit for a server — fix the items above." >&2
    exit 1
fi
echo "check-prod-env: OK"
