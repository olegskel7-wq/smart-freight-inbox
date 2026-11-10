#!/usr/bin/env bash
# One evaluation run (ROADMAP L1): the live pipeline on another model, scored and judged. Dev only.
#
#   scripts/eval/run-eval.sh <label> <short-model> <draft-model> [model-ollama-url]
#
#   scripts/eval/run-eval.sh l1-haiku  claude-haiku-5-5  claude-haiku-5-5
#   scripts/eval/run-eval.sh l1-sonnet claude-sonnet-5-5 claude-sonnet-5-5
#   scripts/eval/run-eval.sh l1-qwen35 qwen3.5:9b qwen3.5:9b http://localhost:11435
#
# Run from Git Bash with the demo harness up (see scripts/demo-mail.ps1). It starts the eval proxy,
# points the demo tenants' Ollama endpoint at it, sends the golden set through the pipeline, then puts
# the endpoint back - also when the run fails half way. Only the demo tenants (the ones with an
# *.example.com inbox) are touched, and only demo mail goes to the model.
set -euo pipefail
cd "$(dirname "$0")/../.."

label=${1:?label}; short=${2:?short-model}; draft=${3:?draft-model}; model_url=${4:-}
port=11500
endpoint="http://host.docker.internal:$port"
log="scripts/eval/runs/$label.calls.jsonl"

sql() { docker compose exec -T postgres psql -U postgres -d smartfreight -AtXq -v ON_ERROR_STOP=1 -f - 2>/dev/null; }

proxy_pid=""
restore() {
  echo "DELETE FROM tenant_setting WHERE key = 'ollama.base-url' AND value = '$endpoint';" | sql || echo "WARNING: could not remove the ollama.base-url override - delete it by hand" >&2
  [ -n "$proxy_pid" ] && kill "$proxy_pid" 2>/dev/null || true
}
trap restore EXIT

mkdir -p scripts/eval/runs
rm -f "$log"
node scripts/eval/ollama-eval-proxy.mjs --port "$port" --short-model "$short" --draft-model "$draft" --log "$log" ${model_url:+--model-url "$model_url"} &
proxy_pid=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do curl -s -o /dev/null -m 2 "http://127.0.0.1:$port/api/tags" && break; sleep 1; done

# Never overwrite an endpoint somebody set on purpose: DO NOTHING on conflict, then insist on ours.
echo "INSERT INTO tenant_setting (tenant_id, key, value)
      SELECT DISTINCT tenant_id, 'ollama.base-url', '$endpoint' FROM tenant_mailbox WHERE username LIKE '%.example.com'
      ON CONFLICT (tenant_id, key) DO NOTHING;" | sql
foreign=$(echo "SELECT count(*) FROM tenant_setting s WHERE s.key = 'ollama.base-url' AND s.value <> '$endpoint'
                  AND s.tenant_id IN (SELECT tenant_id FROM tenant_mailbox WHERE username LIKE '%.example.com');" | sql)
if [ "$foreign" != "0" ]; then
  echo "A demo tenant already has its own ollama.base-url - not touching it. Remove it first." >&2
  exit 1
fi

powershell -NoProfile -ExecutionPolicy Bypass -File scripts/golden-snapshot.ps1 -Capture -Label "$label" -Set all | grep -v "still in pipeline"
restore; trap - EXIT; proxy_pid=""

powershell -NoProfile -ExecutionPolicy Bypass -File scripts/golden-snapshot.ps1 -Score "golden-snapshots/$label.json"
node scripts/eval/judge-drafts.mjs --drafts "golden-snapshots/$label.drafts.json" --calls "$log"
