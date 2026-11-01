import { Fragment } from 'react'
import type { MailboxRow, Settings } from '../../types.ts'
import { formatDateTime } from '../../util/format.ts'
import MailboxFields from './MailboxFields.tsx'
import { EMPTY_MAILBOX, type MailboxEdit, type MailboxForms, toEdit, toRequest } from './mailboxForm.ts'
import { SettingsCard } from './SettingsCard.tsx'
import type { DeleteAndReload, SaveSettings } from './useSettings.ts'

// "Running" only means the adapter is registered; the login probe says whether it can actually log in.
function MailboxStatus({ m }: { m: MailboxRow }) {
  if (!m.enabled) return <span className="badge bg-secondary">Disabled</span>
  if (m.loginOk === false) {
    return (
      <span
        className="badge bg-danger"
        title={`${m.loginError ?? 'Login failed'} — last good login: ${
          m.lastLoginOkAt ? formatDateTime(m.lastLoginOkAt) : 'never'}. No new emails arrive from this mailbox.`}
      >
        Login failed
      </span>
    )
  }
  if (m.running && m.loginOk) {
    return (
      <span className="badge bg-success" title={`Last login check OK: ${formatDateTime(m.lastLoginOkAt)}`}>
        Connected
      </span>
    )
  }
  if (m.running) {
    return (
      <span className="badge bg-success" title="Adapter registered — the first login check runs within ~5 min">
        Running
      </span>
    )
  }
  return (
    <span className="badge bg-warning text-dark" title="Adapter not registered yet — starts on the next reconcile pass">
      Starting…
    </span>
  )
}

// Mailbox table with edit-in-place (one row at a time), enable/disable and delete, plus the add form.
// Add and edit report failures inside their own form; toggle and delete use the page alert.
export default function MailboxesSection({ settings, forms, save, deleteAndReload }: {
  settings: Settings
  forms: MailboxForms
  save: SaveSettings
  deleteAndReload: DeleteAndReload
}) {
  const { newMailbox, setNewMailbox, editing, setEditing, addError, setAddError, editError, setEditError } = forms

  const addMailbox = async (e: React.FormEvent) => {
    e.preventDefault()
    const added = await save(
      (api) => api.post<Settings>('/api/settings/mailboxes', toRequest(newMailbox)),
      'Mailbox added — its IMAP adapter starts within ~30 seconds.',
      setAddError,
    )
    if (added) setNewMailbox(EMPTY_MAILBOX)
  }

  const updateMailbox = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!editing) return
    const saved = await save(
      (api) => api.put<Settings>(`/api/settings/mailboxes/${editing.id}`, toRequest(editing)),
      'Mailbox updated — changes apply within ~30 seconds.',
      setEditError,
    )
    // Keep the form open on an error so the operator can fix the input.
    if (saved) setEditing(null)
  }

  const openEdit = (m: MailboxRow | null) => {
    setEditError(null)
    setEditing(m ? toEdit(m) : null)
  }

  const setEdit = (patch: Partial<MailboxEdit>) =>
    setEditing((prev) => (prev ? { ...prev, ...patch } : prev))

  const toggleMailbox = (id: number, enabled: boolean) =>
    save(
      (api) => api.post<Settings>(`/api/settings/mailboxes/${id}/toggle`, { enabled }),
      enabled ? 'Mailbox enabled.' : 'Mailbox disabled.',
    )

  const deleteMailbox = async (id: number) => {
    if (!window.confirm('Delete this mailbox? Its adapter stops within ~30 seconds.')) return
    await deleteAndReload((api) => api.del<void>(`/api/settings/mailboxes/${id}`), 'Mailbox deleted.')
  }

  return (
    <SettingsCard title="Mailboxes">
      <p className="text-muted small mb-3">
        Inboxes watched for incoming enquiries. Changes apply within ~30 seconds — no restart. To
        rotate a password or change server settings, use Edit — open tickets keep replying through
        the same mailbox (deleting and re-adding it would lose that link).
      </p>

      {!settings.cipherConfigured && (
        <div className="alert alert-warning small">
          <strong>MAILBOX_ENCRYPTION_KEY is not set.</strong> Mailbox passwords cannot be encrypted,
          so mailboxes cannot be saved and no adapters will start. Generate a key with{' '}
          <code>openssl rand -base64 32</code> and set the environment variable.
        </div>
      )}

      {settings.mailboxes.length > 0 ? (
        <table className="table table-sm align-middle">
          <thead>
            <tr className="small text-muted">
              <th>Host</th>
              <th>Port</th>
              <th>Username</th>
              <th>Sends as</th>
              <th>Status</th>
              <th className="text-end">Actions</th>
            </tr>
          </thead>
          <tbody>
            {settings.mailboxes.map((m) => (
              <Fragment key={m.id}>
              <tr>
                <td><code>{m.host}</code></td>
                <td>{m.port}</td>
                <td>{m.username}</td>
                <td>
                  {m.sendsAs ? (
                    <code>{m.sendsAs}</code>
                  ) : (
                    <span className="text-muted small" title="No SMTP configured — replies use the platform account">
                      platform
                    </span>
                  )}
                </td>
                <td><MailboxStatus m={m} /></td>
                <td className="text-end">
                  <button
                    type="button"
                    className="btn btn-outline-primary btn-sm d-block mb-1 ms-auto"
                    style={{ width: 90 }}
                    onClick={() => openEdit(editing?.id === m.id ? null : m)}
                  >
                    {editing?.id === m.id ? 'Cancel' : 'Edit'}
                  </button>
                  <button
                    type="button"
                    className="btn btn-outline-secondary btn-sm d-block mb-1 ms-auto"
                    style={{ width: 90 }}
                    onClick={() => void toggleMailbox(m.id, !m.enabled)}
                  >
                    {m.enabled ? 'Disable' : 'Enable'}
                  </button>
                  <button
                    type="button"
                    className="btn btn-outline-danger btn-sm d-block ms-auto"
                    style={{ width: 90 }}
                    onClick={() => void deleteMailbox(m.id)}
                  >
                    Delete
                  </button>
                </td>
              </tr>
              {editing?.id === m.id && (
                <tr>
                  <td colSpan={6} className="bg-body-tertiary">
                    <form onSubmit={updateMailbox} className="row g-2 py-1">
                      <MailboxFields value={editing} onChange={setEdit} editing error={editError} />
                      <div className="col-12">
                        <button type="submit" className="btn btn-primary btn-sm"
                                disabled={!settings.cipherConfigured && editing.password !== ''}>
                          Save changes
                        </button>
                        <button type="button" className="btn btn-link btn-sm" onClick={() => openEdit(null)}>
                          Cancel
                        </button>
                        <span className="form-text ms-2">
                          Tickets that arrived on this mailbox keep replying through it.
                        </span>
                      </div>
                    </form>
                  </td>
                </tr>
              )}
              </Fragment>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="text-muted small">No mailboxes yet — incoming mail is not being watched.</p>
      )}

      <hr />
      <p className="fw-semibold small mb-2">Add mailbox</p>
      <form onSubmit={addMailbox} className="row g-2">
        <MailboxFields value={newMailbox}
                       onChange={(patch) => setNewMailbox((prev) => ({ ...prev, ...patch }))}
                       editing={false} error={addError} />
        <div className="col-12">
          <button type="submit" className="btn btn-primary btn-sm" disabled={!settings.cipherConfigured}>
            Add mailbox
          </button>
          <span className="form-text ms-2">The password is stored encrypted (AES-256-GCM) and never displayed.</span>
        </div>
      </form>
    </SettingsCard>
  )
}
