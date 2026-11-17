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
# rotate inside the running service. Same for KEYCLOAK_ADMIN_CLIENT_SECRET and
# KEYCLOAK_SEED_OPERATOR_PASSWORD on the Keycloak side: the realm import reads them only when it
# creates the realm (empty keycloak-db); afterwards rotate in Keycloak too (docs/ops/secrets.md).
#
# Gate C3/C4: also refuses a missing or non-public PUBLIC_HOST (Keycloak hostname, token issuer,
# nginx server_name, Let's Encrypt name — docs/ops/tls-domain.md), a Let's Encrypt setup that cannot
# issue (no ACME_EMAIL, `tls` profile off, http not on port 80, staging) and Keycloak in dev mode.

ENV_FILE="${1:-.env}"
[ -f "$ENV_FILE" ] || { echo "check-prod-env: $ENV_FILE not found" >&2; exit 1; }
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
. "$REPO_ROOT/scripts/env-file.sh"

fail=0
problem() { echo "  ✗ $1" >&2; fail=1; }
verdict() {
    [ "$fail" -eq 0 ] && return
    echo "check-prod-env: $ENV_FILE / ${SECRETS_DIR:-?} are not fit for a server — fix the items above." >&2
    exit 1
}
die() { problem "$1"; verdict; }

# --- where the secrets live ------------------------------------------------------------------
# compose prefers a SECRETS_DIR exported in the shell over .env — then the directory checked here
# would not be the one mounted. Refuse rather than guess.
[ -n "${SECRETS_DIR:-}" ] && die "SECRETS_DIR is exported in this shell (compose would use it over $ENV_FILE) — unset it"
SECRETS_DIR="$(value_of SECRETS_DIR)"
[ -n "$SECRETS_DIR" ] || die "SECRETS_DIR is not set in $ENV_FILE (compose would mount ./secrets.dev — the public dev values)"
[ -d "$SECRETS_DIR" ] || die "SECRETS_DIR $SECRETS_DIR does not exist (docs/ops/secrets.md)"
inside_repo "$SECRETS_DIR" && problem "SECRETS_DIR is inside the repo working tree — move it out (e.g. /etc/smartfreight/secrets)"
case "$SECRETS_DIR" in
    /*) ;;
    *) problem "SECRETS_DIR must be an absolute path (a relative one resolves against the repo)" ;;
esac
# Nobody but its owner may list or read the directory (the files themselves are 644 so that every
# container user can read its bind mount — the directory is what keeps other host users out).
mode="$(stat -c %a "$SECRETS_DIR" 2>/dev/null || echo unknown)"
case "$mode" in
    *00) ;;
    *) problem "SECRETS_DIR permissions are $mode — must be 700 (chmod 700 $SECRETS_DIR)" ;;
esac

# secrets.dev is the list; it must match what docker-compose.yml actually mounts.
compose_secrets="$(grep -oE 'secrets\.dev\}/[A-Z0-9_]+' "$REPO_ROOT/docker-compose.yml" | sed 's#.*/##' | sort -u)"
[ "$compose_secrets" = "$(secret_names | sort)" ] \
    || problem "docker-compose.yml top-level secrets and secrets.dev/ list different names — add the missing file/entry"

# The value as the containers see it (missing → empty; reported below). Runs in $(...) — no problem().
secret_of() { secret_in "$SECRETS_DIR" "$1"; }

# Optional secrets: the platform mail account, and the backup/alert ones checked by their gates below.
is_optional() {
    case "$1" in
        IMAP_PASSWORD|SMTP_PASSWORD|TELEGRAM_*|HEALTHCHECKS_*|BACKUP_*|OFFSITE_*|ANTHROPIC_API_KEY|MS365_CLIENT_SECRET) return 0 ;;
        *) return 1 ;;
    esac
}

for name in $(secret_names); do
    f="$SECRETS_DIR/$name"
    if [ ! -f "$f" ]; then
        problem "$name is missing from SECRETS_DIR (compose refuses to start without it; may be empty if optional)"
    elif grep -q "$(printf '\r')" "$f"; then
        problem "$name contains a CR (edited on Windows?) — rewrite it: printf '%s' '<value>' > $name"
    fi
    # A secret must not ALSO sit in .env — that is exactly the cleartext-in-the-working-tree C5 removes.
    grep -qE "$(assignment_re "$name")" "$ENV_FILE" && problem "$name is assigned in $ENV_FILE — secrets belong in SECRETS_DIR only (delete the line)"

    # Every required secret: present, not its secrets.dev value, at least 16 characters. The backend
    # repeats this at startup for the secrets it resolves itself (DevCredentialsGuard, Gate C6).
    { [ -f "$f" ] && ! is_optional "$name"; } || continue
    v="$(secret_of "$name")"
    if [ -z "$v" ]; then
        problem "$name is empty"
    elif [ "$v" = "$(dev_value_of "$name")" ]; then
        problem "$name still has the dev default"
    elif [ "${#v}" -lt 16 ]; then
        problem "$name is shorter than 16 characters"
    fi
done

# The platform mail account: SMTP_PASSWORD no longer falls back to IMAP_PASSWORD (an empty file is
# an empty password, not "unset") — the same account needs the same value in both files.
if [ -n "$(secret_of IMAP_PASSWORD)" ] && [ -z "$(secret_of SMTP_PASSWORD)" ]; then
    problem "IMAP_PASSWORD is set but SMTP_PASSWORD is empty — platform replies would log in with no password (copy it: cp IMAP_PASSWORD SMTP_PASSWORD)"
fi

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

# The seed SUPER_ADMIN password goes through the realm's password policy at import — a value that
# fails it aborts the import and Keycloak never comes up. Keep in sync with keycloak/realm-smartfreight.json.
seed="$(secret_of KEYCLOAK_SEED_OPERATOR_PASSWORD)"
if [ -n "$seed" ]; then
    case "$seed" in *[A-Z]*) ;; *) problem "KEYCLOAK_SEED_OPERATOR_PASSWORD needs an upper-case letter (realm password policy)" ;; esac
    case "$seed" in *[a-z]*) ;; *) problem "KEYCLOAK_SEED_OPERATOR_PASSWORD needs a lower-case letter (realm password policy)" ;; esac
    case "$seed" in *[0-9]*) ;; *) problem "KEYCLOAK_SEED_OPERATOR_PASSWORD needs a digit (realm password policy)" ;; esac
fi

# Gate C3: one public name for everything (Keycloak hostname → token issuer, nginx server_name, the
# SPA client's redirect URIs, the Let's Encrypt certificate). Not localhost, not an IP, not a
# reserved/example name — the backend's DevCredentialsGuard also treats a non-local issuer as public.
host="$(value_of PUBLIC_HOST)"
case "$host" in
    '') problem "PUBLIC_HOST is not set in $ENV_FILE (the server's domain, e.g. app.example.co.uk — docs/ops/tls-domain.md)" ;;
    localhost|*.localhost|*.local|*.test|*.example|*.invalid|example.*|*.example.com|*.example.co.uk)
        problem "PUBLIC_HOST=$host is not a public domain" ;;
    *[!a-z0-9.-]*) problem "PUBLIC_HOST=$host must be a bare lower-case host name (no scheme, port, path or capitals)" ;;
    *.*) case "$host" in *[!0-9.]*) ;; *) problem "PUBLIC_HOST=$host is an IP address — Let's Encrypt and the issuer need a domain" ;; esac ;;
    *) problem "PUBLIC_HOST=$host has no dot — not a public domain" ;;
esac
# Keycloak must run in production mode (Gate C3) — guards against a hand-edit back to start-dev.
grep -qE '^[[:space:]]*command:.*"start-dev"' "$REPO_ROOT/docker-compose.yml" \
    && problem "docker-compose.yml runs Keycloak with start-dev (dev mode) — use start"
# These used to override the issuer/hostname separately; compose no longer reads them, and a stale
# value here would only mislead whoever reads the .env.
for name in KEYCLOAK_FRONTEND_URL KEYCLOAK_ISSUER_URI KEYCLOAK_JWKS_URI; do
    grep -qE "$(assignment_re "$name")" "$ENV_FILE" && problem "$name is set in $ENV_FILE but no longer used — PUBLIC_HOST drives it (delete the line)"
done

# Gate C4: Let's Encrypt (certbot service, profile tls) must be able to issue and renew.
case "$(value_of ACME_EMAIL)" in
    *@*.*) ;;
    *) problem "ACME_EMAIL is not set to an e-mail address in $ENV_FILE (Let's Encrypt account / expiry warnings)" ;;
esac
case ",$(value_of COMPOSE_PROFILES)," in
    *,tls,*) ;;
    *) problem "COMPOSE_PROFILES does not contain tls (no certbot → nginx stays on the self-signed certificate)" ;;
esac
[ "$(value_of NGINX_HTTP_PORT)" = "80" ] || problem "NGINX_HTTP_PORT must be 80 in $ENV_FILE (Let's Encrypt's http-01 check only connects to port 80)"
[ -n "$(value_of ACME_STAGING)" ] && problem "ACME_STAGING is set (staging certificates are not trusted by browsers)"

# Dev-only switches that must be off on a server.
[ -n "$(value_of USE_SYSTEM_CA_CERTS)" ] && problem "USE_SYSTEM_CA_CERTS is set (dev-only Norton CA trust)"
[ "$(value_of DEMO_MAIL_ENABLED)" = "true" ] && problem "DEMO_MAIL_ENABLED=true (replies would go to Mailpit — Gate B14)"
case ",$(value_of COMPOSE_PROFILES)," in
    *,demo,*) problem "COMPOSE_PROFILES contains demo (GreenMail/Mailpit — Gate B14)" ;;
esac

verdict
echo "check-prod-env: OK"
