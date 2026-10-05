#!/bin/sh
# Gate B4: renders Alertmanager's config from env at container start, then runs Alertmanager.
#
# Alertmanager cannot read env vars in its config file, and the bot token is a secret that must
# not live in git — so the config is generated here, into /tmp (never the bind-mounted dir).
# Each channel is optional: unset → that receiver exists but sends nothing, with a loud warning.
#
#   TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID  — every real alert (MailboxDown, BackendDown, ...)
#   HEALTHCHECKS_PING_URL                 — dead man's switch: the always-firing Watchdog alert is
#                                           forwarded every minute; silence = the stack is dead
# Setup steps: docs/ops/alerting.md.
set -eu

CONFIG=/tmp/alertmanager.yml

if [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ]; then
    TELEGRAM_RECEIVER="    telegram_configs:
      - bot_token: '${TELEGRAM_BOT_TOKEN}'
        chat_id: ${TELEGRAM_CHAT_ID}
        parse_mode: ''
        send_resolved: true"
else
    TELEGRAM_RECEIVER=""
    echo "WARNING: TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID not set — alerts are NOT delivered anywhere" >&2
fi

if [ -n "${HEALTHCHECKS_PING_URL:-}" ]; then
    HEARTBEAT_RECEIVER="    webhook_configs:
      - url: '${HEALTHCHECKS_PING_URL}'
        send_resolved: false"
else
    HEARTBEAT_RECEIVER=""
    echo "WARNING: HEALTHCHECKS_PING_URL not set — nobody notices if this whole stack goes down" >&2
fi

cat > "$CONFIG" <<EOF
route:
  receiver: telegram
  # One message per alert and per mailbox/tenant — MailboxDown for two tenants must not merge.
  group_by: [alertname, tenant, mailbox]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
    - matchers: [alertname = "Watchdog"]
      receiver: heartbeat
      group_wait: 0s
      group_interval: 1m
      repeat_interval: 1m

receivers:
  - name: telegram
${TELEGRAM_RECEIVER}
  - name: heartbeat
${HEARTBEAT_RECEIVER}
EOF

exec /bin/alertmanager --config.file="$CONFIG" --storage.path=/alertmanager "$@"
