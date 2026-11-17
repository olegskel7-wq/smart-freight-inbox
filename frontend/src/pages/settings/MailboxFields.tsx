import type { MailboxForm } from './mailboxForm.ts'

// Inputs shared by the add and edit forms. On edit a blank password keeps the stored one; the server
// (not this form) decides when a change re-points the mailbox and asks for the password again.
// Placeholders read as hints ("e.g. …", "default …") so an empty field never looks filled in.
// `error` is the last save failure, shown inside the form: when a table row is being edited the
// page-level alert is scrolled out of view, and the form would just silently stay open.
export default function MailboxFields({ value, onChange, editing, error }: {
  value: MailboxForm
  onChange: (patch: Partial<MailboxForm>) => void
  editing: boolean
  error: string | null
}) {
  return (
    <>
      <div className="col-sm-4">
        <label className="form-label small mb-0">IMAP host</label>
        <input type="text" className="form-control form-control-sm" placeholder="e.g. imap.gmail.com"
               value={value.host} onChange={(e) => onChange({ host: e.target.value })} required />
      </div>
      <div className="col-sm-2">
        <label className="form-label small mb-0">Port</label>
        <input type="number" className="form-control form-control-sm" min={1} max={65535}
               value={value.port} onChange={(e) => onChange({ port: e.target.value })} required />
      </div>
      <div className="col-sm-3">
        <label className="form-label small mb-0">Username</label>
        <input type="text" className="form-control form-control-sm" autoComplete="off"
               value={value.username} onChange={(e) => onChange({ username: e.target.value })} required />
        {editing && (
          <div className="form-text small">Can only be changed until the first email arrives — a new address is a new mailbox.</div>
        )}
      </div>
      <div className="col-sm-3">
        <label className="form-label small mb-0">{editing ? 'New password' : 'Password'}</label>
        <input type="password" className="form-control form-control-sm" autoComplete="new-password"
               placeholder={editing ? 'Leave blank to keep' : undefined} required={!editing}
               value={value.password} onChange={(e) => onChange({ password: e.target.value })} />
        {editing && (
          <div className="form-text small">Required when you change the server, port, username or SMTP.</div>
        )}
      </div>

      <div className="col-12">
        <p className="form-text mb-1 mt-1">
          Outgoing mail (optional). Leave blank to send replies through the platform account. When set,
          replies are sent from this SMTP account, reusing the password above.
        </p>
      </div>
      <div className="col-sm-4">
        <label className="form-label small mb-0">SMTP host</label>
        <input type="text" className="form-control form-control-sm"
               placeholder={editing ? 'Blank = platform account' : 'e.g. smtp.gmail.com'}
               value={value.smtpHost} onChange={(e) => onChange({ smtpHost: e.target.value })} />
      </div>
      <div className="col-sm-2">
        <label className="form-label small mb-0">SMTP port</label>
        <input type="number" className="form-control form-control-sm" placeholder="default 587" min={1} max={65535}
               value={value.smtpPort} onChange={(e) => onChange({ smtpPort: e.target.value })} />
      </div>
      <div className="col-sm-6">
        <label className="form-label small mb-0">From address</label>
        <input type="text" className="form-control form-control-sm" placeholder="Defaults to username" autoComplete="off"
               value={value.fromAddress} onChange={(e) => onChange({ fromAddress: e.target.value })} />
      </div>
      {error && (
        <div className="col-12">
          <div className="alert alert-danger py-1 px-2 small mb-0" role="alert">{error}</div>
        </div>
      )}
    </>
  )
}
