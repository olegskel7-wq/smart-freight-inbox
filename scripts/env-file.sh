#!/bin/sh
# Gate C5: shared by check-prod-env.sh and migrate-env-to-secrets.sh (sourced, never run).
# Callers set ENV_FILE and REPO_ROOT first.

# A .env assignment, optionally `export`-prefixed (compose accepts both) — as an ERE prefix.
assignment_re() { printf '^[[:space:]]*(export[[:space:]]+)?%s=' "$1"; }

# Value of NAME in ENV_FILE. Last assignment wins, as in docker compose; drops a CR (a .env
# prepared on Windows) and one pair of surrounding quotes.
value_of() {
    grep -E "$(assignment_re "$1")" "$ENV_FILE" | tail -n 1 | tr -d '\r' \
        | sed -E "s/^[^=]*=//; s/^[\"'](.*)[\"']\$/\1/"
}

# Every secret docker-compose.yml mounts. secrets.dev holds exactly one file per secret (its dev
# value), so it is the list — a new secret = a new file there + its compose entries
# (check-prod-env.sh verifies the two agree).
secret_names() { ls "$REPO_ROOT/secrets.dev" | grep -v '\.md$'; }

# A secret file's value as its consumers see it (CR/LF stripped, like file-env.sh and configtree);
# a missing file reads as empty.
secret_in() { tr -d '\r\n' < "$1/$2" 2>/dev/null; }

# The public dev value of a secret (what compose mounts when SECRETS_DIR is unset).
dev_value_of() { secret_in "$REPO_ROOT/secrets.dev" "$1"; }

# True when DIR (an existing directory) is inside the repo working tree.
inside_repo() {
    case "$(cd "$1" && pwd -P)/" in
        "$REPO_ROOT"/*) return 0 ;;
        *) return 1 ;;
    esac
}
