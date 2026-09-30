# Extra CA certificates for the backend JVM (dev only)

The backend verifies every mail server's certificate and hostname against the JVM truststore
(`MailTlsPolicy`) — no blanket `ssl.trust`. On a machine whose antivirus inspects TLS (Norton
"Web/Mail Shield" re-signs IMAPS/SMTP traffic, **even from Docker containers**), the real
Gmail / Outlook certificate never reaches the JVM, so tenant mailboxes fail with
`PKIX path building failed`.

This folder is mounted into the backend at `/certificates`. With `USE_SYSTEM_CA_CERTS=1` in
`.env`, the base image's entrypoint (`/__cacert_entrypoint.sh`, eclipse-temurin) imports every
`*.crt` here into the JVM truststore at container start. `*.crt` is gitignored — nothing
machine-specific is committed. Without the variable (the default, and in production) the
folder is ignored.

## Norton (Oleg's machine)

```powershell
# Export Norton's root CA as PEM. Looked up by name: Norton regenerates it (the thumbprint
# changed between June and September 2026) — re-export if mail breaks with PKIX again.
$c = Get-ChildItem Cert:\LocalMachine\Root | Where-Object Subject -like 'CN=Norton Web/Mail Shield Root*' | Select-Object -First 1
"-----BEGIN CERTIFICATE-----`n" + [Convert]::ToBase64String($c.RawData, 'InsertLineBreaks') + "`n-----END CERTIFICATE-----" |
  Set-Content -Encoding ascii certs\extra-ca\norton.crt
```

then add `USE_SYSTEM_CA_CERTS=1` to `.env` and `docker compose up -d backend`. The backend log
starts with `Adding certificate with alias Norton Web\/Mail Shield Root to the JVM truststore`.

Trusting Norton's CA means trusting whatever Norton re-signs — acceptable on a dev box that
already trusts it system-wide, **never on a server**. The alternative is excluding mail ports
from Norton's scanning (Norton settings) and leaving this folder empty.
