#!/usr/bin/env bash
# Gate B10: erase ONE tenant's data — a pilot client who does not convert, or asks (GDPR Art. 17).
#
#   bash scripts/erase-tenant.sh <tenant_id>                              # dry run: what would go
#   bash scripts/erase-tenant.sh <tenant_id> --execute "<exact tenant name>"
#
# Two pilots share one database: every statement is keyed on THIS tenant_id, the exact tenant name
# must be typed back (a wrong id cannot erase the wrong client), and the report proves every OTHER
# tenant's row counts are unchanged. Order of work:
#   1. disable the tenant's mailboxes and wait for the reconciler (no new mail, no catch-up re-ingest)
#   2. one transaction: every tenant-scoped table, then the tenant row
#   3. Keycloak: every user carrying tenant_id=<id> (operators of that client)
#   4. Redis: the tenant's rate-sheet cache keys
# NOT erasable here — say so to the client (docs/ops/data-flow.md, "Deletion"):
#   - encrypted backups: expire by rotation (local ≤ 6 months, offsite ≤ 200 days). Every erasure is
#     appended to docs/ops/erasure-ledger.md: after ANY restore, re-run this script for each entry.
#   - the client's own mailbox (their mail, their drafts): theirs — they revoke the app password /
#     Microsoft consent they gave us.
# Prints a markdown report — keep it as the erasure record. Run from the repo root.
set -euo pipefail
export MSYS_NO_PATHCONV=1

TENANT_ID="${1:?usage: $0 <tenant_id> [--execute \"<exact tenant name>\"]}"
MODE="${2:-dry-run}"
CONFIRM_NAME="${3:-}"
[[ "$TENANT_ID" =~ ^[0-9]+$ ]] || { echo "tenant_id must be a number" >&2; exit 2; }

# Child tables first (FKs), the tenant row last. Every table with a tenant_id column must be here —
# the script refuses to run if the schema has one it does not know (a new feature = update this list).
TABLES=(webhook_delivery webhook_subscription ticket_audit_log training_example quote_follow_up
        shipment_watch outbox_event knowledge_doc email_ticket tenant_document carrier_rate
        tenant_setting tenant_mailbox)

sql() { docker compose exec -T postgres psql -U postgres -d smartfreight -v ON_ERROR_STOP=1 -tAq -c "$1" 2>/dev/null; }

name="$(sql "SELECT name FROM tenant WHERE id = $TENANT_ID")"
[ -n "$name" ] || { echo "No tenant $TENANT_ID" >&2; exit 1; }

known="$(printf '%s\n' "${TABLES[@]}" | sort)"
actual="$(sql "SELECT table_name FROM information_schema.columns WHERE column_name = 'tenant_id' AND table_schema = 'public' ORDER BY 1")"
if [ "$known" != "$(echo "$actual" | tr -d '\r' | sort)" ]; then
    echo "Schema has tenant-scoped tables this script does not cover — update TABLES first:" >&2
    diff <(echo "$known") <(echo "$actual" | tr -d '\r' | sort) >&2 || true
    exit 1
fi

counts() {  # $1 = SQL predicate on tenant_id → "table=n ..." per table
    for t in "${TABLES[@]}"; do printf '%s=%s ' "$t" "$(sql "SELECT count(*) FROM $t WHERE $1")"; done
}
kc() { docker compose exec -T keycloak sh -c "/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8081 --realm master --user \"\$KC_BOOTSTRAP_ADMIN_USERNAME\" --password \"\$KC_BOOTSTRAP_ADMIN_PASSWORD\" >/dev/null 2>&1 && /opt/keycloak/bin/kcadm.sh $1" 2>/dev/null; }
kc_user_ids() {  # operators of this tenant, via the same q=tenant_id: search KeycloakAdminService uses
    # Guard: if the attribute filter were ever ignored (wrong syntax, API change) the search would
    # return EVERY user — another client's operators included. A tenant id that cannot exist must
    # match nobody, or we stop before deleting anything.
    local canary
    canary="$(kc "get users -r smartfreight -q q=tenant_id:-999999 --fields id --format csv --noquotes" | tr -d '\r' | grep -cE '^[0-9a-f-]{36}$' || true)"
    if [ "$canary" != 0 ]; then
        echo "Keycloak attribute search is not filtering (canary matched $canary users) — refusing." >&2
        exit 1
    fi
    kc "get users -r smartfreight -q q=tenant_id:$TENANT_ID --fields id --format csv --noquotes" \
        | tr -d '\r' | grep -E '^[0-9a-f-]{36}$' || true
}
redis_keys() { docker compose exec -T redis sh -c "redis-cli -a \"\$REDIS_PASSWORD\" --no-auth-warning --scan --pattern 'rateSheets::$TENANT_ID:*'" | tr -d '\r'; }

before_mine="$(counts "tenant_id = $TENANT_ID")"
before_others="$(counts "tenant_id <> $TENANT_ID")"
kc_ids="$(kc_user_ids)"
kc_count="$(echo -n "$kc_ids" | grep -c . || true)"
rkeys="$(redis_keys)"

if [ "$MODE" != "--execute" ]; then
    echo "DRY RUN — tenant $TENANT_ID \"$name\" — nothing changed"
    echo "Postgres: $before_mine"
    echo "Keycloak users with tenant_id=$TENANT_ID: $kc_count"
    echo "Redis cache keys: $(echo -n "$rkeys" | grep -c . || true)"
    echo "To erase: bash $0 $TENANT_ID --execute \"$name\""
    exit 0
fi
[ "$CONFIRM_NAME" = "$name" ] || { echo "Name mismatch: tenant $TENANT_ID is \"$name\" — refusing." >&2; exit 1; }

started="$(date -u +%FT%TZ)"
# 1. stop ingestion first — the reconciler drops adapters of disabled mailboxes within ~30 s, and
#    the health/catch-up pass only touches enabled ones.
sql "UPDATE tenant_mailbox SET enabled = false WHERE tenant_id = $TENANT_ID" >/dev/null
sleep 40

# 2. one transaction — all or nothing. postgres is superuser: RLS does not apply, the WHERE does.
{
    echo "BEGIN;"
    for t in "${TABLES[@]}"; do echo "DELETE FROM $t WHERE tenant_id = $TENANT_ID;"; done
    echo "DELETE FROM tenant WHERE id = $TENANT_ID;"
    echo "COMMIT;"
} | docker compose exec -T postgres psql -U postgres -d smartfreight -v ON_ERROR_STOP=1 -q 2>/dev/null

# 3. Keycloak operators of this tenant
for id in $kc_ids; do kc "delete users/$id -r smartfreight" >/dev/null; done
# 4. cache
for key in $rkeys; do
    docker compose exec -T redis sh -c "redis-cli -a \"\$REDIS_PASSWORD\" --no-auth-warning del '$key'" >/dev/null
done

after_mine="$(counts "tenant_id = $TENANT_ID")"
after_others="$(counts "tenant_id <> $TENANT_ID")"
tenant_left="$(sql "SELECT count(*) FROM tenant WHERE id = $TENANT_ID")"
kc_left="$(kc_user_ids | grep -c . || true)"
ok=1
[[ "$after_mine" =~ ^(([a-z_]+=0 )+)$ ]] && [ "$tenant_left" = 0 ] && [ "$kc_left" = 0 ] || ok=0
[ "$before_others" = "$after_others" ] || ok=0

printf '%s | %s | tenant %s "%s" | %s\n' "$(date -u +%F)" "$started" "$TENANT_ID" "$name" \
    "$([ "$ok" = 1 ] && echo erased || echo INCOMPLETE)" >> docs/ops/erasure-ledger.md

cat <<EOF
# Erasure record — tenant $TENANT_ID "$name"

**Result: $([ "$ok" = 1 ] && echo "✅ ERASED" || echo "❌ INCOMPLETE — investigate")** · started $started · finished $(date -u +%FT%TZ)

| Store | Before | After |
|---|---|---|
| Postgres, this tenant | $before_mine | $after_mine |
| Tenant row | 1 | $tenant_left |
| Keycloak users (tenant_id=$TENANT_ID) | $kc_count | $kc_left |
| Redis cache keys | $(echo -n "$rkeys" | grep -c . || true) | 0 |
| **Other tenants (must be unchanged)** | $before_others | $after_others |

Not erased by this procedure (see docs/ops/data-flow.md): encrypted backups until rotation (local ≤ 6
months, offsite ≤ 200 days; ledger entry added — re-run after any restore) and the client's own mailbox.
EOF
[ "$ok" = 1 ]
