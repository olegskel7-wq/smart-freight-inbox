# Gate A / A1 — Secret rotation evidence (06 Jul 2026)

Артефакт-свидетельство по правилу зачёта Gate v2 (пункт закрыт только с артефактом).

## Что ротировано

| Секрет | Было | Стало |
|---|---|---|
| Gmail App Password (`smartfreight.test.2026@gmail.com`) | выдан 13.06.2026, лежал открытым текстом в `.env` (`IMAP_PASSWORD`/`SMTP_PASSWORD`) | старый пароль **отозван** в Google Account (удалён из App passwords), новый вписан в `.env` вручную Олегом — значение нигде не публиковалось (ни в чат, ни в git) |
| `MAILBOX_ENCRYPTION_KEY` (AES-256-GCM мастер-ключ для `tenant_mailbox.password_enc` и webhook-секретов) | не менялся с 13.06.2026 | новый 32-байтовый ключ сгенерирован `RandomNumberGenerator` (PS 5.1, `Create()`+`GetBytes` — статического `Fill` в 5.1 нет), записан в `.env` с `-Encoding ascii` |

## Почему re-encrypt не понадобился

Аудит зашифрованных данных перед ротацией (psql, 06.07):

- `tenant_mailbox` — **1 строка** (id=1, Gmail-ящик): пароль в ней и так менялся → строка удалена,
  `MailboxBootstrap` реимпортировал ящик из `.env` при рестарте (новый пароль, новый ключ, новая строка id=2)
- `webhook_subscription` — **1 строка** (id=2, `https://httpstat.us/200`): мёртвый тестовый endpoint,
  авто-отключён 25.06 после 4 неудач → удалена вместе с 4 строками `webhook_delivery`
- Других потребителей `SecretCipher` в проде нет → старым ключом расшифровывать больше нечего,
  затирание безопасно

## Верификация (лог-свидетельства)

1. Ключ в `.env`: длина 44 base64-символа (=32 байта), не нулевой, файл ASCII, mtime 06.07 19:12
2. Рестарт backend (`docker compose up -d backend`, образ не менялся):
   ```
   18:15:07 INFO  c.s.inbox.InboxApplication - Started InboxApplication in 7.676 seconds
   18:15:07 INFO  c.s.inbox.startup.MailboxBootstrap - Imported env-configured mailbox
            smartfreight.test.2026@gmail.com@imap.gmail.com:993 as Default-tenant mailbox
   18:15:12 INFO  c.s.i.i.MailboxImapAdapterManager - IMAP adapter started: mailbox 2
            (smartfreight.test.2026@gmail.com@imap.gmail.com:993) for tenant 1
   ```
   0 строк ERROR за окно старта + 45 с окна реконсайлера; `AuthenticationFailedException` нет
3. `tenant_mailbox` после рестарта: новая строка id=2, enabled, `password_enc` перешифрован новым ключом
4. **E2E на новых кредах**: тестовое письмо → тикет **id=82**, `STATUS_UPDATE`, `DRAFT_READY`
   (18:19:16) — IMAP-приём, классификация, extraction и драфтинг прошли полный цикл
5. Побочное свидетельство ротации: Google прислал «Оповещение системы безопасности» о смене
   app password → тикеты 80–81, корректно классифицированы `IRRELEVANT`

## Остаточный риск (не входит в A1)

- `.env` по-прежнему в рабочем дереве открытым текстом — вынос в Docker secrets = **C5** (Gate C)
- Ключ по-прежнему env-level custody — Vault = волна **W5**
