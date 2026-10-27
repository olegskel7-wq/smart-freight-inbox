#!/bin/sh
# Gate C5: the one way anything reads a secret inside a container whose image only knows env vars
# (RabbitMQ, Keycloak, Redis, Alertmanager, rclone). Exports every file compose mounted under
# /run/secrets as an env var of the same name — the compose `target:` is the variable the image
# expects — then execs the real entrypoint. The values live in this process tree only: never in
# docker-compose.yml, `.env`, `docker inspect` or the repo.
#   entrypoint: ["/bin/sh", "/file-env.sh", <real entrypoint>...]
# `docker compose exec` sessions do NOT inherit those vars — there, source it first (no arguments =
# export only): `. /file-env.sh && redis-cli ...` (healthchecks, erase-tenant.sh, restore-test.sh).
# No `set -e/-u` here: when sourced it would leak into the caller's shell.

for f in /run/secrets/*; do
    [ -f "$f" ] || continue
    # A secret file edited on Windows may carry a CR — never let it into a password.
    value="$(tr -d '\r' < "$f")"
    export "${f##*/}=$value"
done

[ "$#" -eq 0 ] || exec "$@"
