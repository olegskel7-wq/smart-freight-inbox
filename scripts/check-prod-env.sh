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

# Last assignment wins, as in docker compose. Strips one pair of surrounding quotes.
value_of() {
    grep -E "^[[:space:]]*$1=" "$ENV_FILE" | tail -n 1 | sed -E "s/^[^=]*=//; s/^[\"'](.*)[\"']\$/\\1/"
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
