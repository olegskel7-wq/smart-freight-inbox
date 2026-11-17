#!/bin/sh
# Runs from /docker-entrypoint.d before nginx starts (Gate C4). Three jobs:
#   1. render nginx.conf (a template) with PUBLIC_HOST into conf.d/default.conf;
#   2. point /etc/nginx/tls/server.{crt,key} at the right certificate:
#        - Let's Encrypt for PUBLIC_HOST, once certbot has issued it (/etc/letsencrypt/live/<host>/);
#        - else the local pair in /etc/nginx/certs (mkcert or self-signed — generated here when the
#          dir is empty, i.e. a fresh clone or the first start on a server before certbot ran);
#   3. leave a background loop that re-checks every 10 minutes and reloads nginx when the
#      certificate changed — first issuance and every renewal are picked up without a restart.
# On a real host (anything but localhost) it also adds a catch-all server that refuses requests for
# any other name (an IP scan, a spoofed Host header) instead of serving the app to them.

set -e

PUBLIC_HOST="${PUBLIC_HOST:-localhost}"
case "$PUBLIC_HOST" in
    *[!A-Za-z0-9.-]*|'') echo "[nginx-init] PUBLIC_HOST '$PUBLIC_HOST' is not a host name" >&2; exit 1 ;;
esac

LOCAL_DIR=/etc/nginx/certs
LE_DIR="/etc/letsencrypt/live/${PUBLIC_HOST}"
TLS_DIR=/etc/nginx/tls

# --- 1. config ---------------------------------------------------------------------------------
# Only ${PUBLIC_HOST} is substituted: nginx variables ($host, $remote_addr, …) must survive.
envsubst '${PUBLIC_HOST}' < /etc/nginx/smartfreight/nginx.conf.template > /etc/nginx/conf.d/default.conf

case "$PUBLIC_HOST" in
    localhost|127.0.0.1) rm -f /etc/nginx/conf.d/unknown-host.conf ;;
    *)
        # Any other name (or none — an IP literal sends no SNI): TLS handshake refused, plain http
        # connection closed without a response. Let's Encrypt's http-01 request carries PUBLIC_HOST,
        # so it reaches the real server block.
        cat > /etc/nginx/conf.d/unknown-host.conf <<'EOF'
server {
    listen 443 ssl default_server;
    server_name _;
    ssl_reject_handshake on;
}
server {
    listen 80 default_server;
    server_name _;
    return 444;
}
EOF
        # The catch-all is the default now; the real server blocks answer only their own name.
        sed -i 's/ default_server;/;/' /etc/nginx/conf.d/default.conf
        ;;
esac

# --- 2. certificate ----------------------------------------------------------------------------
if [ ! -f "${LOCAL_DIR}/server.crt" ] || [ ! -f "${LOCAL_DIR}/server.key" ]; then
    echo "[nginx-init] Generating a self-signed certificate for ${PUBLIC_HOST} in ${LOCAL_DIR}"
    mkdir -p "${LOCAL_DIR}"
    apk add --no-cache openssl >/dev/null 2>&1 || true
    openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
        -keyout "${LOCAL_DIR}/server.key" -out "${LOCAL_DIR}/server.crt" \
        -subj "/O=SmartFreight/CN=${PUBLIC_HOST}" -addext "subjectAltName=DNS:${PUBLIC_HOST}" 2>/dev/null
fi

# Prints which certificate is active ("letsencrypt" / "local") after pointing the symlinks at it.
pick_cert() {
    if [ -s "${LE_DIR}/fullchain.pem" ] && [ -s "${LE_DIR}/privkey.pem" ]; then
        crt="${LE_DIR}/fullchain.pem"; key="${LE_DIR}/privkey.pem"; src=letsencrypt
    else
        crt="${LOCAL_DIR}/server.crt"; key="${LOCAL_DIR}/server.key"; src=local
    fi
    mkdir -p "${TLS_DIR}"
    ln -sfn "$crt" "${TLS_DIR}/server.crt"
    ln -sfn "$key" "${TLS_DIR}/server.key"
    echo "$src"
}

# Content fingerprint of the active pair: changes on first issuance, on a renewal (certbot swaps the
# live/ symlinks to new archive files) and on a hand-replaced local cert.
fingerprint() { cat "${TLS_DIR}/server.crt" "${TLS_DIR}/server.key" 2>/dev/null | md5sum; }

echo "[nginx-init] ${PUBLIC_HOST}: using the $(pick_cert) certificate"

# --- 3. pick up issuance / renewals ------------------------------------------------------------
(
    last="$(fingerprint)"
    while :; do
        sleep 600
        src="$(pick_cert)"
        now="$(fingerprint)"
        if [ "$now" != "$last" ] && nginx -t -q 2>/dev/null; then
            echo "[nginx-tls] certificate changed (${src}) — reloading nginx"
            nginx -s reload && last="$now"
        fi
    done
) &
