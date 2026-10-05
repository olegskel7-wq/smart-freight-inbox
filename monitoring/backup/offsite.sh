#!/bin/sh
# Gate B1: copies the local pg_dump files (postgres-backup + keycloak-backup → postgres_backups
# volume) offsite, encrypted, every OFFSITE_INTERVAL_SECONDS (default 1 h). Before this, the only
# backups sat on the same host as the database: a lost VPS took data and backups together.
#
# After every successful pass it pings BACKUP_HEALTHCHECKS_PING_URL; a failed pass pings /fail.
# A day without a ping → healthchecks.io alerts (period 1 h, grace ~3 h). Setup: docs/ops/backup-restore.md.
set -u

. /etc/offsite/offsite-env.sh
if [ "$OFFSITE_READY" != 1 ]; then
    echo "offsite: not configured — idling (see warnings above)" >&2
    exec sleep infinity
fi

ping_hc() {
    [ -n "${BACKUP_HEALTHCHECKS_PING_URL:-}" ] && wget -q -T 10 -O /dev/null "${BACKUP_HEALTHCHECKS_PING_URL}$1" || true
}

while true; do
    # daily/weekly/monthly hold real files (hard links); last/ only symlinks to them.
    if rclone copy /backups vault: --exclude "last/**" --exclude "*-latest.sql.gz" --stats-one-line -v \
        && rclone delete vault: --min-age "${OFFSITE_RETENTION_DAYS:-200}d" -v; then
        echo "offsite: copy OK $(date -u +%FT%TZ)"
        ping_hc ""
    else
        echo "offsite: copy FAILED $(date -u +%FT%TZ)" >&2
        ping_hc "/fail"
    fi
    sleep "${OFFSITE_INTERVAL_SECONDS:-3600}"
done
