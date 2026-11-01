// Static help: where the platform fallback SMTP account and the mailbox encryption key are configured.
export default function SmtpHowToCard() {
  return (
    <div className="card shadow-sm border-0 bg-light">
      <div className="card-body">
        <p className="fw-semibold mb-2">How to change SMTP</p>
        <p className="small text-muted mb-2">
          Per-tenant outgoing SMTP is managed per mailbox above — no restart needed. The platform fallback
          account (used when a mailbox has no SMTP of its own) comes from the <code>.env</code> file in the
          project root:
        </p>
        <pre className="bg-white border rounded p-3 small mb-2">{`SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USER=your@gmail.com
SMTP_PASSWORD=your-app-password
MAILBOX_ENCRYPTION_KEY=<openssl rand -base64 32>`}</pre>
        <p className="small text-muted mb-0">Then restart: <code>docker-compose up --build backend</code></p>
      </div>
    </div>
  )
}
