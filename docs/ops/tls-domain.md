# Domain, Keycloak production mode and Let's Encrypt (Gates C3 + C4)

What is in the code (branch `gate-c/keycloak-prod-tls`, Nov 2026) and the steps that are left for the
day the VPS and the domain exist. Everything is driven by `.env` — no code or config file is edited.

## How it fits together

One variable, `PUBLIC_HOST` (a bare host name, e.g. `app.example.co.uk`; unset = `localhost`), feeds:

| Where | What it becomes |
|---|---|
| Keycloak (`docker-compose.yml`) | `KC_HOSTNAME=https://$PUBLIC_HOST` → the token `iss` |
| backend | `KEYCLOAK_ISSUER_URI=https://$PUBLIC_HOST/realms/smartfreight` (the expected `iss`) — same source, cannot drift. A non-local issuer also arms `DevCredentialsGuard` (refuses dev secrets at startup) |
| realm import | `smartfreight-spa` redirect URI / web origin / post-logout URI `https://$PUBLIC_HOST/*` (`${SF_PUBLIC_HOST}` placeholder) |
| SPA | nothing — the bundle uses the page's own origin + `/realms/smartfreight` (same origin as nginx) |
| nginx | `server_name`, the self-signed fallback's CN/SAN, the Let's Encrypt directory it reads; on a real host a catch-all refuses every other name (TLS handshake rejected / http 444) |
| certbot | the certificate it requests |

Keycloak runs `start` (production mode) everywhere, the local stack included: http only inside the
compose network (TLS ends at nginx, `KC_PROXY_HEADERS=xforwarded`), backend traffic stays on
`keycloak:8081` (`KC_HOSTNAME_BACKCHANNEL_DYNAMIC`), health + metrics on the management port 9000
(Prometheus job `keycloak`). nginx proxies only `/realms/` and `/resources/` — the admin console
(`/admin`) is not reachable from outside; administer with `kcadm.sh` inside the container (below).

TLS: nginx listens on 443 and on container port 80 (host port `NGINX_HTTP_PORT`: 8443 locally, 80 on
the server). Port 80 serves `/.well-known/acme-challenge/` from a shared volume and redirects
everything else to https. The `certbot` service (compose profile `tls`) issues the certificate on its
first start (http-01, webroot) and runs `certbot renew` twice a day. nginx's entrypoint points
`/etc/nginx/tls/server.{crt,key}` at `/etc/letsencrypt/live/$PUBLIC_HOST/` once that exists, else at
`monitoring/nginx/certs/` (mkcert pair, or a self-signed one it generates), and re-checks every
10 minutes — the first issuance and each renewal are picked up with an `nginx -s reload`, no restart.

## On the VPS (once)

Prerequisites: the general server setup (Docker, the repo checked out, UFW allowing 22/80/443) and the
secrets of [`secrets.md`](secrets.md), including `KEYCLOAK_SEED_OPERATOR_PASSWORD` — **before the first
`up`**: the realm import reads the admin-client secret and the seed password only when it creates the
realm on an empty `keycloak-db`.

1. **DNS.** At the registrar: an `A` record `app.<domain>` → the VPS's IPv4 (and `AAAA` → IPv6 only if
   the VPS really answers on it — Let's Encrypt prefers IPv6 when the record exists). Wait until
   `dig +short app.<domain>` returns the VPS address.
2. **`.env`** on the server, next to `SECRETS_DIR` and the rest from `.env.example`:
   ```bash
   PUBLIC_HOST=app.<domain>
   ACME_EMAIL=<your e-mail>        # Let's Encrypt account: expiry warnings, recovery
   NGINX_HTTP_PORT=80
   COMPOSE_PROFILES=tls
   ACME_STAGING=1                  # first run only — see step 4
   ```
   Starting certbot with `ACME_EMAIL` set means agreeing to the Let's Encrypt Subscriber Agreement
   (the service passes `--agree-tos`) — read it once: https://letsencrypt.org/repository/.
3. **Check** (expect exactly one complaint, `ACME_STAGING is set`; everything else must pass):
   ```bash
   sh scripts/check-prod-env.sh .env
   ```
4. **Dry run on staging** — catches DNS/firewall mistakes without burning the production rate limit
   (5 failed validations per host per hour):
   ```bash
   docker compose up -d
   docker compose logs -f certbot      # wait for "Successfully received certificate"
   ```
   Within 10 minutes `docker compose logs nginx | grep nginx-tls` shows the reload; the browser then
   shows a certificate from "(STAGING) Let's Encrypt" (untrusted — expected).
5. **Switch to the real certificate:** delete the line `ACME_STAGING=1`, remove the staging
   certificate, restart certbot:
   ```bash
   docker compose run --rm --entrypoint certbot certbot delete --cert-name app.<domain> --non-interactive
   docker compose up -d certbot
   docker compose logs -f certbot
   sh scripts/check-prod-env.sh .env   # must print: check-prod-env: OK
   ```
6. **Verify** (from your own machine):
   ```bash
   curl -sI http://app.<domain>/tickets | head -3        # 301 → https
   curl -sI https://app.<domain>/tickets | grep -i strict  # HSTS present (real host)
   curl -s https://app.<domain>/realms/smartfreight/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
   curl -s -o /dev/null -w '%{http_code}\n' https://app.<domain>/api/tickets   # 401
   ```
   The issuer must be `https://app.<domain>/realms/smartfreight`. Then log in through the browser as
   `operator@smartfreight.com` with the seed password, open `/tickets` (the "● Live" badge = WebSocket
   up), and check https://www.ssllabs.com/ssltest/ (expect A). Finally the external nmap of Gate C
   (only 22/80/443 open; 80 only redirects and serves the challenge).

## Later

- **Renewals** are automatic. Let's Encrypt has stopped expiry e-mails, so watch it yourself: the
  certificate is valid 90 days and renewed from day 60; `docker compose logs certbot` shows failures.
  A manual check: `docker compose run --rm --entrypoint certbot certbot certificates`.
- **Changing the domain** of an EXISTING installation: the realm already exists, so its SPA client keeps
  the old redirect URIs — add the new `https://<new>/*` (redirect URI + post-logout) and web origin
  `https://<new>` to `smartfreight-spa` with `kcadm.sh`, then change `PUBLIC_HOST` and
  `docker compose up -d`. Every operator logs in again (new issuer).
- **Keycloak admin** (the console is not proxied): from the server,
  ```bash
  docker compose exec keycloak sh -c '. /file-env.sh && /opt/keycloak/bin/kcadm.sh config credentials \
    --server http://localhost:8081 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" \
    && /opt/keycloak/bin/kcadm.sh get clients -r smartfreight -q clientId=smartfreight-spa --fields redirectUris'
  ```
  For the web console use an SSH tunnel to `127.0.0.1:8081` — but with `KC_HOSTNAME` set it redirects
  to the public name, so `kcadm.sh` is the dependable path.

## Locally

Nothing to set: `PUBLIC_HOST` unset = `localhost`, nginx on the mkcert/self-signed pair in
`monitoring/nginx/certs/`, plain-http redirect on host port 8443, no certbot (profile `tls` off).
To try the production shape on a test name (what was done on 17 Nov 2026, see the ROADMAP C3/C4 line):
hosts entry `127.0.0.1 <name>`, `PUBLIC_HOST=<name>` in a throwaway compose project with fresh volumes,
`check-prod-env.sh` against a scratch `.env`. Let's Encrypt itself cannot be exercised without a real
domain — certbot idles on reserved names (`*.test`, `*.local`, `localhost`, example domains).

## What only the VPS can prove

- Let's Encrypt issuance and the first renewal (needs public DNS + port 80 reachable from the internet).
- The browser trust chain and the SSL Labs grade.
- HSTS on the real name (locally it is off on `localhost` by design).
- The external nmap (only 22/80/443).
