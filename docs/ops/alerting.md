# Alerting setup (Gate B4)

Prometheus evaluates `monitoring/alerts.yml` → Alertmanager (`alertmanager:9093`) → **Telegram**.
An always-firing `Watchdog` alert is forwarded every minute to an **external heartbeat**: if the
host, Prometheus or Alertmanager dies, the pings stop and the external service alerts you — the one
failure this stack cannot report about itself. A third, external check pings the public health URL.

All three are configured through `.env`; `scripts/check-prod-env.sh` refuses a server `.env` without
them. Locally they are optional (the Alertmanager log warns when a channel is off).

## 1. Telegram (every real alert)

1. In Telegram open **@BotFather** → `/newbot` → name it (e.g. `SmartFreight Alerts`) → copy the token.
2. Send any message to the new bot (a bot cannot message you first).
3. Get your chat id: open `https://api.telegram.org/bot<TOKEN>/getUpdates` → `"chat":{"id": ...}`.
   For a group: add the bot to the group, post a message, same URL (group ids are negative).
4. `.env`:
   ```
   TELEGRAM_BOT_TOKEN=<token>
   TELEGRAM_CHAT_ID=<chat id>
   ```

## 2. Dead man's switch (the whole stack is down)

1. Create a free check on **healthchecks.io**: period **1 min**, grace **5 min**; add your email
   (and/or its Telegram integration) as the notification channel.
2. Copy the ping URL (`https://hc-ping.com/<uuid>`) → `.env`: `HEALTHCHECKS_PING_URL=<url>`.

## 3. External uptime ping (the site is unreachable from the internet) — on the VPS only

Add an HTTP check (healthchecks.io, UptimeRobot, …) on `https://<domain>/actuator/health`,
expecting HTTP 200, every 1–5 min. nginx exposes only this actuator path (Gate C2).

## Apply and test

```
docker compose up -d alertmanager prometheus
docker compose logs alertmanager | grep -i warning          # no output = all channels on
```

Send a test alert straight to Alertmanager (arrives in Telegram within ~30 s):

```
docker compose exec alertmanager amtool alert add TestAlert severity=warning \
  --annotation=summary="Test from SmartFreight" --alertmanager.url=http://localhost:9093
```

What reaches Telegram: `MailboxDown` (tenant + mailbox id; error on /settings), `BackendDown`,
`RabbitMQDown`, `OutboxRelayStalled`, `ReplySendFailures`, `CircuitBreakerOpen`, SLA breaches and the
rest of `alerts.yml`. One message per alert × tenant × mailbox, repeated every 4 h while firing.
