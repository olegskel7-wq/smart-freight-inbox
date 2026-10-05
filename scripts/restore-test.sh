#!/usr/bin/env bash
# Gate B2: prove the backups restore. A backup nobody has restored is a belief, not a backup.
#
#   bash scripts/restore-test.sh [local|offsite] [report.md]
#
#   local   — newest dumps from the postgres_backups volume (default)
#   offsite — newest dumps pulled back from the ENCRYPTED offsite copy (backup-offsite / rclone
#             vault:) and decrypted — the real disaster drill: the host is gone, only the bucket
#             and the passphrase are left
#
# Restores smartfreight + keycloak into a throwaway pgvector container (never the live DB), then
# checks: every table's row count against the live DB, Flyway history, the vector extension, RLS
# under the app role (tenant-scoped + fail-closed) and Keycloak users/realm. Prints a markdown
# report (written to the 2nd arg too). Exit 0 = restore proven. Run from the repo root.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: don't rewrite container paths

SOURCE="${1:-local}"
REPORT="${2:-}"
TEST_PG=sf-restore-test
APP_ROLE=smartfreight_app
WORK="$(mktemp -d)"
trap 'docker rm -f "$TEST_PG" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

live() { docker compose exec -T postgres psql -U postgres -d smartfreight -tAqc "$1" 2>/dev/null; }
test_sql() { docker exec -i "$TEST_PG" psql -U postgres -d "$1" -v ON_ERROR_STOP=1 -tAq "${@:2}"; }

# --- 1. fetch the newest dumps ---------------------------------------------------------------
case "$SOURCE" in
  local)
    for db in smartfreight keycloak; do
      docker compose exec -T postgres-backup sh -c "cat /backups/last/$db-latest.sql.gz" > "$WORK/$db.sql.gz"
      echo "$db: /backups/last/$(docker compose exec -T postgres-backup readlink /backups/last/$db-latest.sql.gz | tr -d '\r')" >> "$WORK/sources"
    done ;;
  offsite)
    for db in smartfreight keycloak; do
      file="$(docker compose run --rm -T --entrypoint /bin/sh backup-offsite -c \
          ". /etc/offsite/offsite-env.sh >/dev/null 2>&1; rclone lsf vault:daily/ --include '$db-*.sql.gz' | sort | tail -n 1" | tr -d '\r')"
      [ -n "$file" ] || { echo "restore-test: no $db dump offsite" >&2; exit 1; }
      docker compose run --rm -T --entrypoint /bin/sh backup-offsite -c \
          ". /etc/offsite/offsite-env.sh >/dev/null 2>&1; rclone cat vault:daily/$file" > "$WORK/$db.sql.gz"
      echo "$db: offsite vault:daily/$file (decrypted)" >> "$WORK/sources"
    done ;;
  *) echo "usage: $0 [local|offsite] [report.md]" >&2; exit 2 ;;
esac
for db in smartfreight keycloak; do gzip -t "$WORK/$db.sql.gz"; done

# --- 2. restore into a throwaway server ----------------------------------------------------------
docker rm -f "$TEST_PG" >/dev/null 2>&1 || true
docker run -d --name "$TEST_PG" -e POSTGRES_PASSWORD=restore-test pgvector/pgvector:pg16 >/dev/null
for _ in $(seq 1 30); do docker exec "$TEST_PG" pg_isready -U postgres -q 2>/dev/null && break; sleep 1; done
sleep 2
# pg_dump carries no roles: the app role must exist before the GRANTs in the dump (on a real
# rebuild: CREATE ROLE ... PASSWORD '<DB_APP_PASSWORD>' — docs/ops/backup-restore.md).
docker exec "$TEST_PG" psql -U postgres -v ON_ERROR_STOP=1 -q \
    -c "CREATE ROLE $APP_ROLE LOGIN PASSWORD 'restore-test' NOSUPERUSER NOBYPASSRLS" \
    -c "CREATE DATABASE smartfreight" -c "CREATE DATABASE keycloak" -c "CREATE ROLE keycloak LOGIN"
start=$(date +%s)
for db in smartfreight keycloak; do
  gunzip -c "$WORK/$db.sql.gz" | test_sql "$db" >/dev/null
done
restore_secs=$(( $(date +%s) - start ))

# --- 3. verify ----------------------------------------------------------------------------------
fail=0
rows=""
tables="$(test_sql smartfreight -c "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY 1")"
for t in $tables; do
  got="$(test_sql smartfreight -c "SELECT count(*) FROM \"$t\"")"
  exp="$(live "SELECT count(*) FROM \"$t\"" | tr -d '\r')"
  mark="✅"; [ "$got" = "$exp" ] || mark="≠ (live changed since the dump?)"
  rows+="| $t | $got | $exp | $mark |"$'\n'
done

flyway="$(test_sql smartfreight -c "SELECT max(version::int) || ' (' || count(*) || ' applied, ' || count(*) FILTER (WHERE NOT success) || ' failed)' FROM flyway_schema_history WHERE version ~ '^[0-9]+$'")"
vector="$(test_sql smartfreight -c "SELECT extversion FROM pg_extension WHERE extname='vector'")"
rls_tables="$(test_sql smartfreight -c "SELECT count(*) FROM pg_class WHERE relrowsecurity AND relforcerowsecurity")"
total_tickets="$(test_sql smartfreight -c "SELECT count(*) FROM email_ticket")"
t1_expected="$(test_sql smartfreight -c "SELECT count(*) FROM email_ticket WHERE tenant_id = 1")"
# The app's view of the restored data: RLS keyed on the GUC the app sets on every connection.
as_app() { docker exec -i "$TEST_PG" psql -U "$APP_ROLE" -d smartfreight -tAq -c "$1"; }
t1_seen="$(as_app "SET app.tenant_id = '1'; SELECT count(*) FROM email_ticket")"
none_seen="$(as_app "SELECT count(*) FROM email_ticket")"
[ "$t1_seen" = "$t1_expected" ] && rls_tenant="✅ tenant 1 sees its $t1_seen of $total_tickets tickets" || { rls_tenant="❌ tenant 1 saw $t1_seen, expected $t1_expected"; fail=1; }
[ "$none_seen" = "0" ] && rls_closed="✅ no tenant set → 0 rows" || { rls_closed="❌ no tenant set → $none_seen rows"; fail=1; }
[ -n "$vector" ] || fail=1
kc_users="$(test_sql keycloak -c "SELECT count(*) FROM user_entity")"
kc_realms="$(test_sql keycloak -c "SELECT string_agg(name, ', ' ORDER BY name) FROM realm")"
case "$kc_realms" in *smartfreight*) ;; *) fail=1 ;; esac

# --- 4. report ----------------------------------------------------------------------------------
out="$(cat <<EOF
# Restore test (Gate B2) — $(date -u +%F' '%H:%M) UTC

**Result: $([ "$fail" = 0 ] && echo "✅ RESTORE PROVEN" || echo "❌ FAILED")** · source: \`$SOURCE\` · restore took ${restore_secs}s · throwaway \`pgvector/pgvector:pg16\`

Dumps:
$(sed 's/^/- /' "$WORK/sources")

| Check | Result |
|---|---|
| Flyway (numbered migrations) | $flyway |
| pgvector extension | ${vector:-❌ missing} |
| Tables with RLS forced | $rls_tables |
| RLS as \`$APP_ROLE\`, tenant set | $rls_tenant |
| RLS as \`$APP_ROLE\`, no tenant | $rls_closed |
| Keycloak realms | $kc_realms |
| Keycloak users | $kc_users |

| Table | Restored rows | Live rows now | |
|---|---|---|---|
$rows
EOF
)"
echo "$out"
[ -n "$REPORT" ] && printf '%s\n' "$out" > "$REPORT"
exit "$fail"
