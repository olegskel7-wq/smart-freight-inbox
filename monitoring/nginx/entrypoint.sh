#!/bin/sh
# Generate a self-signed certificate on first run so the nginx container
# can serve HTTPS without any manual setup. For production, replace
# /etc/nginx/certs/server.crt and server.key with a real cert/key pair.

set -e

CERT_DIR=/etc/nginx/certs
CRT="${CERT_DIR}/server.crt"
KEY="${CERT_DIR}/server.key"

if [ ! -f "${CRT}" ] || [ ! -f "${KEY}" ]; then
    echo "[nginx-init] Generating self-signed certificate at ${CERT_DIR}"
    mkdir -p "${CERT_DIR}"
    apk add --no-cache openssl >/dev/null 2>&1 || true
    openssl req -x509 -nodes -newkey rsa:2048 \
        -days 365 \
        -keyout "${KEY}" \
        -out "${CRT}" \
        -subj "/C=GB/ST=Suffolk/L=Felixstowe/O=SmartFreight/CN=smartfreight.local"
    echo "[nginx-init] Done. WARNING: self-signed — replace with a real cert before going live."
else
    echo "[nginx-init] Reusing existing certificate"
fi
