#!/bin/sh
# Gate C4: the certbot service's whole life (docker-compose.yml, profile `tls`).
#   - no certificate for PUBLIC_HOST yet → issue one (http-01 via nginx's /.well-known/acme-challenge/
#     on port 80, files written to the shared acme_webroot volume);
#   - then twice a day `certbot renew` (renews only within 30 days of expiry, otherwise a no-op).
# nginx notices a new certificate by itself (monitoring/nginx/entrypoint.sh, every 10 min).
# Runbook: docs/ops/tls-domain.md. Starting this service with ACME_EMAIL set is the operator agreeing
# to the Let's Encrypt Subscriber Agreement (--agree-tos below) — the runbook says so.

PUBLIC_HOST="${PUBLIC_HOST:-localhost}"
WEBROOT=/var/www/acme

# Stop promptly on `docker compose stop` (sleep runs in the background, `wait` is interruptible).
trap 'exit 0' TERM INT

# Not set up to issue: stay up doing nothing (an exit would loop under restart: unless-stopped).
idle() { echo "[certbot] $1 Idling." >&2; while :; do sleep 86400 & wait $!; done; }

case "$PUBLIC_HOST" in
    # Same reserved names check-prod-env.sh refuses (keep the two lists in sync).
    localhost|*.localhost|*.local|*.test|*.example|*.invalid|example.*|*.example.com|*.example.co.uk|*[!a-z0-9.-]*)
        idle "PUBLIC_HOST=$PUBLIC_HOST is not a public name — Let's Encrypt cannot issue for it." ;;
esac
[ -n "${ACME_EMAIL:-}" ] || idle "ACME_EMAIL is not set (the Let's Encrypt account and its recovery need it)."

staging=""
[ -n "${ACME_STAGING:-}" ] && staging="--staging" && echo "[certbot] ACME_STAGING set — Let's Encrypt STAGING (untrusted test certificate)"

mkdir -p "$WEBROOT"

while :; do
    if [ ! -s "/etc/letsencrypt/live/${PUBLIC_HOST}/fullchain.pem" ]; then
        echo "[certbot] requesting a certificate for ${PUBLIC_HOST}"
        # --cert-name pins the live/<host>/ directory nginx looks in (no -0001 suffix on a retry).
        certbot certonly --non-interactive --webroot -w "$WEBROOT" \
            -d "$PUBLIC_HOST" --cert-name "$PUBLIC_HOST" \
            --email "$ACME_EMAIL" --agree-tos --no-eff-email \
            $staging \
            || echo "[certbot] issuance failed — DNS for ${PUBLIC_HOST} must point here and port 80 must be open; retrying in 1 h" >&2
        # A failed attempt counts against Let's Encrypt's rate limit (5 failures/hour/host) — wait.
        [ -s "/etc/letsencrypt/live/${PUBLIC_HOST}/fullchain.pem" ] || { sleep 3600 & wait $!; continue; }
    fi
    certbot renew --non-interactive --webroot -w "$WEBROOT" --quiet \
        || echo "[certbot] renew failed — will retry in 12 h (the certificate is valid for weeks yet)" >&2
    sleep 43200 & wait $!
done
