#!/bin/sh
# Gate C5: entrypoint wrapper for images that read secrets only from env vars (RabbitMQ, Keycloak,
# Alertmanager, rclone). Exports every file compose mounted under /run/secrets as an env var of the
# same name — the compose `target:` is the variable the image expects — then execs the real
# entrypoint. The values live in this process tree only: never in docker-compose.yml, `.env`,
# `docker inspect` or the repo. Note `docker compose exec` sessions do NOT inherit them — scripts
# read /run/secrets/<name> directly. Usage: entrypoint: ["/bin/sh", "/file-env.sh", <entrypoint>...]
# Sourced with no arguments (`. /file-env.sh`), it only exports — scripts/restore-test.sh does that.
set -eu

for f in /run/secrets/*; do
    [ -f "$f" ] || continue
    # A secret file edited on Windows may carry a CR — never let it into a password.
    value="$(tr -d '\r' < "$f")"
    export "${f##*/}=$value"
done

exec "$@"
