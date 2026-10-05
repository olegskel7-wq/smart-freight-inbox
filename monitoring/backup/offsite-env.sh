#!/bin/sh
# Gate B1: rclone remotes for the offsite copy, built from env (sourced by offsite.sh and by
# scripts/restore-test.sh). Sets OFFSITE_READY=1 when a target is configured.
#
#   store: — the bucket (S3-compatible: AWS S3 London eu-west-2, or any S3 endpoint), or a local
#            dir for drills (OFFSITE_LOCAL_DIR) — never both
#   vault: — rclone crypt over store: — dumps are encrypted HERE with BACKUP_ENCRYPTION_PASSPHRASE
#            before upload: they hold clients' customer email; the storage provider never can read
#            them. File names stay readable (dates), contents do not.
# Lose the passphrase and every offsite dump is unreadable — keep it outside the server (docs/ops/backup-restore.md).

OFFSITE_READY=0
if [ -z "${BACKUP_ENCRYPTION_PASSPHRASE:-}" ]; then
    echo "WARNING: BACKUP_ENCRYPTION_PASSPHRASE not set — no offsite copy" >&2
elif [ -n "${OFFSITE_S3_BUCKET:-}" ]; then
    export RCLONE_CONFIG_STORE_TYPE=s3
    export RCLONE_CONFIG_STORE_PROVIDER="${OFFSITE_S3_PROVIDER:-AWS}"
    export RCLONE_CONFIG_STORE_REGION="${OFFSITE_S3_REGION:-eu-west-2}"
    export RCLONE_CONFIG_STORE_ENDPOINT="${OFFSITE_S3_ENDPOINT:-}"
    export RCLONE_CONFIG_STORE_ACCESS_KEY_ID="${OFFSITE_S3_ACCESS_KEY_ID:-}"
    export RCLONE_CONFIG_STORE_SECRET_ACCESS_KEY="${OFFSITE_S3_SECRET_ACCESS_KEY:-}"
    export RCLONE_CONFIG_STORE_NO_CHECK_BUCKET=true
    OFFSITE_BASE="store:${OFFSITE_S3_BUCKET}/smartfreight"
    OFFSITE_READY=1
elif [ -n "${OFFSITE_LOCAL_DIR:-}" ]; then
    export RCLONE_CONFIG_STORE_TYPE=local
    OFFSITE_BASE="store:${OFFSITE_LOCAL_DIR}"
    OFFSITE_READY=1
else
    echo "WARNING: neither OFFSITE_S3_BUCKET nor OFFSITE_LOCAL_DIR set — no offsite copy" >&2
fi

if [ "$OFFSITE_READY" = 1 ]; then
    export RCLONE_CONFIG_VAULT_TYPE=crypt
    export RCLONE_CONFIG_VAULT_REMOTE="$OFFSITE_BASE"
    export RCLONE_CONFIG_VAULT_FILENAME_ENCRYPTION=off
    export RCLONE_CONFIG_VAULT_DIRECTORY_NAME_ENCRYPTION=false
    RCLONE_CONFIG_VAULT_PASSWORD="$(rclone obscure "$BACKUP_ENCRYPTION_PASSPHRASE")"
    export RCLONE_CONFIG_VAULT_PASSWORD
fi
