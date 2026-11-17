#!/bin/sh
# Gate C5: starts Redis with its password from the REDIS_PASSWORD secret. The password goes into a
# root-written, redis-only config file — NOT the command line (`--requirepass x` shows in `ps` and
# `docker inspect`) and not the redis process env. An empty secret refuses to start: `requirepass ""`
# would silently turn auth off. The image entrypoint still drops to the redis user because its first
# argument is `redis-server` (upstream ENTRYPOINT docker-entrypoint.sh — recheck on an image upgrade).
set -eu
. /file-env.sh

[ -n "${REDIS_PASSWORD:-}" ] || { echo "REDIS_PASSWORD is empty — refusing to start without auth" >&2; exit 1; }
umask 077
printf 'requirepass "%s"\n' "$REDIS_PASSWORD" > /tmp/redis.conf
chown redis /tmp/redis.conf
unset REDIS_PASSWORD

exec docker-entrypoint.sh redis-server /tmp/redis.conf --appendonly yes
