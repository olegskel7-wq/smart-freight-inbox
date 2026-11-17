import { useId, useState } from 'react'
import { useApi } from '../../api/useApi.ts'
import type { Ms365Status, Settings } from '../../types.ts'
import { type Ms365Form, toMs365Request } from './ms365Form.ts'
import { formatDateTime } from '../../util/format.ts'
import type { SaveSettings } from './useSettings.ts'

// Microsoft 365 mailboxes (V38). A customer's Microsoft organisation is never typed in: its
// administrator signs in through a link this tenant issues, and the server takes the organisation id
// from Microsoft's reply. Only organisations connected that way can carry an M365 mailbox.

/** Fields of the add / edit form for an M365 mailbox. */
export function Ms365Fields({ value, onChange, status, editing, error }: {
  value: Ms365Form
  onChange: (patch: Partial<Ms365Form>) => void
  status: Ms365Status
  editing: boolean
  error: string | null
}) {
  const id = useId()
  return (
    <>
      <div className="col-sm-4">
        <label className="form-label small mb-0" htmlFor={`${id}-dir`}>Microsoft 365 organisation</label>
        <select id={`${id}-dir`} className="form-select form-select-sm" required value={value.directoryId}
                onChange={(e) => onChange({ directoryId: e.target.value })}>
          <option value="">Choose…</option>
          {status.directories.map((d) => (
            <option key={d.directoryId} value={d.directoryId}>
              {d.boundBy ? `${d.boundBy.split('@')[1] ?? d.boundBy} · ` : ''}{d.directoryId.slice(0, 8)}…
            </option>
          ))}
        </select>
      </div>
      <div className="col-sm-4">
        <label className="form-label small mb-0" htmlFor={`${id}-user`}>Mailbox address</label>
        <input id={`${id}-user`} type="email" className="form-control form-control-sm" autoComplete="off" required
               placeholder="e.g. ops@yourcompany.co.uk"
               value={value.username} onChange={(e) => onChange({ username: e.target.value })} />
        {editing && (
          <div className="form-text small">Can only be changed until the first email arrives — a new address is a new mailbox.</div>
        )}
      </div>
      <div className="col-sm-4">
        <label className="form-label small mb-0" htmlFor={`${id}-from`}>Send replies as</label>
        <input id={`${id}-from`} type="email" className="form-control form-control-sm" disabled={!value.sendReplies}
               placeholder="default: the mailbox address"
               value={value.fromAddress} onChange={(e) => onChange({ fromAddress: e.target.value })} />
      </div>
      <div className="col-12">
        <div className="form-check form-check-inline small">
          <input className="form-check-input" type="checkbox" id={`${id}-send`}
                 checked={value.sendReplies} onChange={(e) => onChange({ sendReplies: e.target.checked })} />
          <label className="form-check-label" htmlFor={`${id}-send`}>
            Send replies from this mailbox (otherwise they go out through the platform account)
          </label>
        </div>
      </div>
      {error && <div className="col-12"><div className="alert alert-danger small py-1 mb-0">{error}</div></div>}
    </>
  )
}

const EMPTY: Ms365Form = { directoryId: '', username: '', sendReplies: true, fromAddress: '' }

/** "Connect Microsoft 365" + the connected organisations + the add-M365-mailbox form. */
export function Ms365Panel({ status, reload, save }: {
  status: Ms365Status | null
  reload: () => void
  save: SaveSettings
}) {
  const api = useApi()
  const [link, setLink] = useState<string | null>(null)
  const [linkError, setLinkError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [form, setForm] = useState<Ms365Form>(EMPTY)
  const [addError, setAddError] = useState<string | null>(null)

  if (!status) return null
  if (!status.configured) {
    return (
      <p className="text-muted small mb-0">
        Microsoft 365 mailboxes are not available on this server yet (the platform&apos;s Microsoft app,
        MS365_CLIENT_ID, is not set up).
      </p>
    )
  }

  const createLink = async () => {
    setLinkError(null)
    setCopied(false)
    try {
      const r = await api.post<{ url: string }>('/api/settings/ms365/consent-link')
      setLink(r.url)
    } catch (e) {
      setLinkError(e instanceof Error ? e.message : 'Could not create the link.')
    }
  }

  const copy = async () => {
    if (!link) return
    try {
      await navigator.clipboard.writeText(link)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  const add = async (e: React.FormEvent) => {
    e.preventDefault()
    const added = await save(
      (a) => a.post<Settings>('/api/settings/mailboxes/ms365', toMs365Request(form)),
      'Microsoft 365 mailbox added — its adapter starts within ~30 seconds.',
      setAddError,
    )
    if (added) setForm(EMPTY)
  }

  return (
    <>
      <p className="fw-semibold small mb-1">Microsoft 365 / Outlook</p>
      <p className="text-muted small mb-2">
        Outlook and Microsoft 365 mailboxes connect without a password. First connect your Microsoft 365
        organisation: create a link and send it to its administrator (valid 24 h, single use). They sign
        in with Microsoft and approve SmartFreight; the organisation then appears below.
      </p>
      <div className="d-flex flex-wrap gap-2 align-items-center mb-2">
        <button type="button" className="btn btn-outline-primary btn-sm" onClick={() => void createLink()}>
          Connect Microsoft 365 organisation
        </button>
        {status.directories.length > 0 && (
          <button type="button" className="btn btn-link btn-sm" onClick={reload}>Refresh</button>
        )}
      </div>
      {linkError && <div className="alert alert-danger small py-1">{linkError}</div>}
      {link && (
        <div className="input-group input-group-sm mb-2">
          <input type="text" readOnly className="form-control" value={link} aria-label="Consent link"
                 onFocus={(e) => e.target.select()} />
          <button type="button" className="btn btn-outline-secondary" onClick={() => void copy()}>
            {copied ? 'Copied' : 'Copy'}
          </button>
          <a className="btn btn-outline-secondary" href={link} target="_blank" rel="noreferrer">Open</a>
        </div>
      )}

      {status.directories.length > 0 ? (
        <>
          <ul className="small mb-2">
            {status.directories.map((d) => (
              <li key={d.directoryId}>
                <code>{d.directoryId}</code>
                {d.boundBy && <> — connected by {d.boundBy}</>} on {formatDateTime(d.boundAt)}
              </li>
            ))}
          </ul>
          <form onSubmit={add} className="row g-2">
            <Ms365Fields value={form} onChange={(p) => setForm((prev) => ({ ...prev, ...p }))}
                         status={status} editing={false} error={addError} />
            <div className="col-12">
              <button type="submit" className="btn btn-primary btn-sm">Add Microsoft 365 mailbox</button>
              <span className="form-text ms-2">
                Its administrator must also give SmartFreight access to this mailbox (Exchange step in the setup guide).
              </span>
            </div>
          </form>
        </>
      ) : (
        <p className="text-muted small mb-0">No Microsoft 365 organisation connected yet.</p>
      )}
    </>
  )
}
