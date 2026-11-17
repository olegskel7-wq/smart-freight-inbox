# Microsoft 365 mailboxes — OAuth2 setup (V38)

Exchange Online no longer accepts passwords for IMAP (since 2022), and SMTP AUTH with a password
is switched **off by default at the end of December 2026** (Message Center MC786329, revised
27 Jan 2026; final removal to be announced in H2 2027). A customer on Outlook / Microsoft 365
can therefore only be connected with OAuth2.

How SmartFreight does it: **app-only (client credentials) + XOAUTH2 over the existing IMAP/SMTP
pipeline**. One multi-tenant app registration belongs to us. Each customer's admin consents to it
in their own directory and grants its service principal access to exactly one mailbox. No customer
password is ever stored. The mailbox row keeps only the customer's directory id
(`tenant_mailbox.ms_directory_id`). Our app's client id lives in `.env`, its secret in `SECRETS_DIR/MS365_CLIENT_SECRET`.

Three parts:

1. Get a test Microsoft 365 tenant with a mailbox (once, by us).
2. Register our app in Entra (once, by us).
3. Give the app access to a mailbox. The customer's Exchange admin repeats this for every
   mailbox. For the test tenant, we do it ourselves.

---

## 1. A test Microsoft 365 tenant

**Option A: Microsoft 365 Developer Program (free E5 sandbox).** Try this first; it takes 5 minutes.
Join at <https://developer.microsoft.com/microsoft-365/dev-program>. In 2026 the sandbox is only
offered to qualifying members, for example Visual Studio Professional/Enterprise subscribers or
Microsoft partner programme members. If the dashboard says *"You don't currently qualify for a
sandbox subscription"*, don't wait for an appeal; go to option B.

**Option B: a Microsoft 365 Business trial (1 month free, card required).**
1. On the UK Microsoft 365 business plans page, pick **Business Basic** (or Business Standard,
   whichever offers *Try free for one month*). Basic is enough: we only need an Exchange Online mailbox.
2. Sign up as the Ltd. Choose the tenant name carefully: it becomes `<name>.onmicrosoft.com` and
   cannot be renamed. Example: `smartfreightdev`.
3. You end up as the global admin, `admin@<name>.onmicrosoft.com`. Turn on MFA for it when asked.
4. **Billing:** in the admin centre → *Billing → Your products*, turn **recurring billing off**
   unless you decide to keep the tenant. A tenant that is kept (~£5/user/month for Basic) is worth it
   if it becomes the Ltd's permanent home for the app registration (see the note in step 2).

**Create the test mailbox:** admin centre <https://admin.microsoft.com> → *Users → Active users →
Add a user* → e.g. `ops@<name>.onmicrosoft.com`, assign the licence. Mailbox provisioning takes
from a few minutes up to about an hour. Check it by signing in at <https://outlook.office.com>
and sending yourself an email.

---

## 2. Register the SmartFreight app in Entra

> Note on where the app lives: an app registration belongs to the directory it was created in. If
> the test tenant is a trial you will delete, the app is deleted with it. That is fine for
> development: re-register in the Ltd's permanent tenant before the first customer. A **kept**
> Business Basic tenant can be that permanent home from day one.

1. <https://entra.microsoft.com> → *Identity → Applications → App registrations → New registration*.
   - Name: `SmartFreight Mail Connector`
   - Supported account types: **Accounts in any organizational directory (Multitenant)**. Customers
     consent to this app from their own directories.
   - Redirect URI: platform **Web**, `https://<PUBLIC_HOST>/ms365/consent/callback` (the server's
     domain, `docs/ops/tls-domain.md`). Add `https://localhost/ms365/consent/callback` as well for the
     local stack (Authentication → Add URI). Microsoft returns the consent flow there.
2. On the app's *Overview*, note the **Application (client) ID** and the **Directory (tenant) ID**.
3. *API permissions → Add a permission → APIs my organization uses* → search
   **Office 365 Exchange Online** → **Application permissions** → tick
   - `IMAP.AccessAsApp`
   - `SMTP.SendAsApp`

   → *Add permissions*. Then click **Grant admin consent for \<tenant\>**. Both rows must show
   a green *Granted*. The default delegated `Microsoft Graph / User.Read` can be removed; we
   don't use it.
4. *Token configuration → Add groups claim* → tick **Directory roles** → *ID* token → *Add*. This puts
   the signed-in user's admin roles (`wids`) into the ID token; SmartFreight connects an organisation
   only for an administrator (Global / Privileged Role / Cloud Application / Application Administrator)
   and refuses everyone else. Without this step every consent ends with "Sign in with an administrator
   account".
5. *Certificates & secrets → Client secrets → New client secret* → expiry 12 months → copy the
   **Value** column immediately. It is shown once. Do not copy the *Secret ID*.
   Put a calendar reminder 2 weeks before expiry: an expired secret stops **every** M365 mailbox,
   and you would only find out when the `MailboxDown` alert fires.
6. Store them yourself. Never paste them in chat or commit them. The client id is not secret and goes
   into `.env`; the secret is a file in `SECRETS_DIR` (Gate C5, [`secrets.md`](secrets.md)), like every other secret:

   ```
   # .env
   MS365_CLIENT_ID=<Application (client) ID>
   ```
   ```bash
   # Git Bash on Windows: /c/Users/<you>/.smartfreight/secrets ; server: /etc/smartfreight/secrets
   printf '%s' '<secret Value>' > "$SECRETS_DIR/MS365_CLIENT_SECRET"
   docker compose up -d backend
   ```

   Keep a copy of the secret in your password manager, next to `MAILBOX_ENCRYPTION_KEY`.

---

## 3. Give the app access to one mailbox (Exchange Online PowerShell)

Consent alone does **not** open any mailbox. Exchange needs its own service principal for the app,
plus an explicit permission on each mailbox it may read. This is the least-privilege part: the app
reaches only the mailboxes listed here.

1. Get the **Object ID of the Enterprise application**. This is NOT the app registration's object id:
   Entra → *Enterprise applications* → `SmartFreight Mail Connector` → *Overview → Object ID*.
2. In PowerShell (Windows PowerShell 5.1 is fine):

   ```powershell
   Install-Module -Name ExchangeOnlineManagement -Scope CurrentUser
   Connect-ExchangeOnline -UserPrincipalName admin@<name>.onmicrosoft.com

   # Register the app's service principal with Exchange (once per customer directory)
   New-ServicePrincipal -AppId <client-id> -ObjectId <enterprise-app-object-id> -DisplayName "SmartFreight Mail Connector"

   # Allow it into exactly this mailbox (IMAP read + mark-as-read + Drafts)
   Add-MailboxPermission -Identity "ops@<name>.onmicrosoft.com" -User <enterprise-app-object-id> -AccessRights FullAccess

   # Replies go out over SMTP AUTH: new tenants have it OFF org-wide; enable it for this mailbox only
   Set-CASMailbox -Identity "ops@<name>.onmicrosoft.com" -SmtpClientAuthenticationDisabled $false

   # Check: IMAP on, SMTP AUTH not disabled for the mailbox
   Get-CASMailbox -Identity "ops@<name>.onmicrosoft.com" | Format-List ImapEnabled, SmtpClientAuthenticationDisabled
   ```

   Permission changes can take **up to ~30 minutes** to apply. A login that fails right after these
   commands is not yet a reason to debug.

3. Connect it in SmartFreight (`/settings` → *Mailboxes* → *Microsoft 365 / Outlook*), as the tenant's
   ADMIN:
   1. **Connect Microsoft 365 organisation** → a link appears (valid 24 h, single use). Send it to the
      customer's Microsoft 365 administrator (for the test tenant: open it yourself).
   2. The administrator signs in with Microsoft and approves *SmartFreight Mail Connector*. They end on a
      SmartFreight page "Microsoft 365 is connected".
   3. Back on `/settings` (Refresh): the organisation is listed. **Add Microsoft 365 mailbox** → pick the
      organisation, type the mailbox address, keep *Send replies from this mailbox* on.

   The organisation id is never typed in: SmartFreight takes it from the ID token Microsoft returns for
   the signed-in administrator, and one Microsoft organisation can belong to only one SmartFreight
   tenant. That is what stops one SmartFreight customer from pointing a mailbox at another customer's
   organisation (our app is the same for both). IMAP `outlook.office365.com:993` and SMTP
   `smtp.office365.com:587` are filled in by the server.

---

## Troubleshooting

| Symptom | Cause → fix |
|---|---|
| `AADSTS7000215: Invalid client secret` | The *Secret ID* was copied instead of the *Value*, or the secret expired → create a new secret. |
| `AADSTS700016: Application … was not found in the directory` | The customer never consented → run the consent link again. |
| "Sign in with an administrator account" after consent | The person is not an admin, or step 2.4 (groups claim *Directory roles*) is missing on the app. |
| "already connected to another SmartFreight account" | That Microsoft organisation is bound to another tenant. Remove the binding there (support, psql) first. |
| `AADSTS50011: The redirect URI … does not match` | Register exactly `https://<PUBLIC_HOST>/ms365/consent/callback` (step 2.1); `MS365_CONSENT_REDIRECT_URI` follows `PUBLIC_HOST`. |
| `AADSTS7000229` / missing service principal | Consent not granted in that directory → *Grant admin consent*. |
| IMAP `AUTHENTICATE failed` | `New-ServicePrincipal` / `Add-MailboxPermission` missing, wrong Object ID (the app registration's instead of the Enterprise app's), or not propagated yet (wait 30 min). |
| SMTP `535 5.7.139 … SmtpClientAuthentication is disabled` | `Set-CASMailbox … -SmtpClientAuthenticationDisabled $false` for this mailbox. |
| SMTP `535 5.7.3 Authentication unsuccessful` | `SMTP.SendAsApp` lacks admin consent, or the mailbox permission is missing. |

## Revoking access (pilot exit, GDPR erasure)

On the customer side: `Remove-MailboxPermission -Identity <mailbox> -User <object-id> -AccessRights FullAccess`,
then delete the Enterprise application in their Entra. On our side: delete the mailbox on `/settings`
(or `scripts/erase-tenant.sh`). No credential of theirs remains anywhere to rotate.
