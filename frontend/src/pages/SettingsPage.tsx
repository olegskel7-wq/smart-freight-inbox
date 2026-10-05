import { Fragment, useCallback, useEffect, useState } from 'react'
import { useApi } from '../api/useApi.ts'
import { useOperators } from '../api/useOperators.ts'
import PageHeader from '../components/PageHeader.tsx'
import type { MailboxRow, Settings } from '../types.ts'
import { formatDateTime } from '../util/format.ts'

type Msg = { kind: 'success' | 'danger' | 'warning'; text: string } | null

// The add and edit mailbox forms share one shape; ports are strings while typed.
type MailboxForm = {
  host: string
  port: string
  username: string
  password: string
  smtpHost: string
  smtpPort: string
  fromAddress: string
}
type MailboxEdit = MailboxForm & { id: number }

const EMPTY_MAILBOX: MailboxForm = {
  host: '', port: '993', username: '', password: '', smtpHost: '', smtpPort: '', fromAddress: '',
}

const toEdit = (m: MailboxRow): MailboxEdit => ({
  id: m.id,
  host: m.host,
  port: String(m.port),
  username: m.username,
  password: '',
  smtpHost: m.smtpHost ?? '',
  smtpPort: m.smtpPort != null ? String(m.smtpPort) : '',
  fromAddress: m.fromAddress ?? '',
})

// Blank optionals go as null so the server applies its defaults (993 / 587, inbound-only, from = username).
const toRequest = (f: MailboxForm) => ({
  host: f.host,
  port: f.port ? Number(f.port) : null,
  username: f.username,
  password: f.password,
  smtpHost: f.smtpHost || null,
  smtpPort: f.smtpPort ? Number(f.smtpPort) : null,
  fromAddress: f.fromAddress || null,
})

// Inputs shared by the add and edit forms. On edit a blank password keeps the stored one; the server
// (not this form) decides when a change re-points the mailbox and asks for the password again.
// Placeholders read as hints ("e.g. …", "default …") so an empty field never looks filled in.
// `error` is the last save failure, shown inside the form: when a table row is being edited the
// page-level alert is scrolled out of view, and the form would just silently stay open.
function MailboxFields({ value, onChange, editing, error }: {
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

// React port of settings.html (Phase 3.2). Each card saves its own section through /api/settings/*;
// a successful save returns the refreshed snapshot, which re-seeds the form. The platform SMTP block is
// read-only (environment-sourced). The old Thymeleaf page stays live until cutover.
export default function SettingsPage() {
  const api = useApi()

  const [data, setData] = useState<Settings | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [msg, setMsg] = useState<Msg>(null)

  // Editable form state, seeded from the loaded snapshot.
  const [ollamaUrl, setOllamaUrl] = useState('')
  const [draftEnabled, setDraftEnabled] = useState(false)
  const [autoSendEnabled, setAutoSendEnabled] = useState(false)
  const [autoSendThreshold, setAutoSendThreshold] = useState('0.8')
  const [autoSendTypes, setAutoSendTypes] = useState<string[]>([])
  const [statusUpdateEnabled, setStatusUpdateEnabled] = useState(false)
  const [statusUpdateMilestones, setStatusUpdateMilestones] = useState('')
  const [quoteFollowUpEnabled, setQuoteFollowUpEnabled] = useState(false)
  const [quoteFollowUpDelayHours, setQuoteFollowUpDelayHours] = useState('48')
  const [smartRoutingEnabled, setSmartRoutingEnabled] = useState(true)
  const [escalationAssignee, setEscalationAssignee] = useState('')
  // Operator list for the escalation-assignee dropdown (Phase 3.10). Best-effort: a non-admin can't
  // read /api/users (403) → the selector just falls back to "no operators".
  const operators = useOperators()
  const [extractionPrompt, setExtractionPrompt] = useState('')
  const [draftingPrompt, setDraftingPrompt] = useState('')

  // New-mailbox form, and the edit-in-place form (Gate B15) for one mailbox at a time (null = closed).
  const [newMailbox, setNewMailbox] = useState<MailboxForm>(EMPTY_MAILBOX)
  const [editing, setEditing] = useState<MailboxEdit | null>(null)
  const [addError, setAddError] = useState<string | null>(null)
  const [editError, setEditError] = useState<string | null>(null)

  const applyData = useCallback((s: Settings) => {
    setData(s)
    setOllamaUrl(s.ollamaUrlOverride)
    setDraftEnabled(s.draftEnabled)
    setAutoSendEnabled(s.autoSendEnabled)
    setAutoSendThreshold(s.autoSendThreshold)
    setAutoSendTypes(s.autoSendTypes)
    setStatusUpdateEnabled(s.statusUpdateEnabled)
    setStatusUpdateMilestones(s.statusUpdateMilestones)
    setQuoteFollowUpEnabled(s.quoteFollowUpEnabled)
    setQuoteFollowUpDelayHours(s.quoteFollowUpDelayHours)
    setSmartRoutingEnabled(s.smartRoutingEnabled)
    setEscalationAssignee(s.escalationAssignee)
    setExtractionPrompt(s.extractionPrompt)
    setDraftingPrompt(s.draftingPrompt)
  }, [])

  const load = useCallback(async () => {
    setError(null)
    try {
      applyData(await api.get<Settings>('/api/settings'))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load settings')
    }
  }, [api, applyData])

  useEffect(() => {
    void load()
  }, [load])

  // Runs a save call, re-seeds from its refreshed snapshot and reports the outcome. `after` lets a
  // section add its own follow-up message (e.g. the auto-send "no types" warning). Resolves to whether
  // the save succeeded, so a form can reset or close only then.
  const save = async (call: () => Promise<Settings>, ok: string, after?: (s: Settings) => Msg) => {
    setMsg(null)
    try {
      const s = await call()
      applyData(s)
      setMsg(after?.(s) ?? { kind: 'success', text: ok })
      return true
    } catch (e) {
      setMsg({ kind: 'danger', text: e instanceof Error ? e.message : 'Save failed' })
      return false
    }
  }

  const toggleType = (t: string) =>
    setAutoSendTypes((prev) => (prev.includes(t) ? prev.filter((x) => x !== t) : [...prev, t]))

  // Like save(), but a failure is reported next to the mailbox form (via setError) instead of in the
  // page-level alert, which is off-screen while a table row is being edited.
  const saveMailbox = async (call: () => Promise<Settings>, ok: string,
                             setError: (text: string | null) => void) => {
    setMsg(null)
    setError(null)
    try {
      applyData(await call())
      setMsg({ kind: 'success', text: ok })
      return true
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save failed')
      return false
    }
  }

  const addMailbox = async (e: React.FormEvent) => {
    e.preventDefault()
    const added = await saveMailbox(
      () => api.post<Settings>('/api/settings/mailboxes', toRequest(newMailbox)),
      'Mailbox added — its IMAP adapter starts within ~30 seconds.',
      setAddError,
    )
    if (added) setNewMailbox(EMPTY_MAILBOX)
  }

  const updateMailbox = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!editing) return
    const saved = await saveMailbox(
      () => api.put<Settings>(`/api/settings/mailboxes/${editing.id}`, toRequest(editing)),
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
      () => api.post<Settings>(`/api/settings/mailboxes/${id}/toggle`, { enabled }),
      enabled ? 'Mailbox enabled.' : 'Mailbox disabled.',
    )

  const deleteMailbox = async (id: number) => {
    if (!window.confirm('Delete this mailbox? Its adapter stops within ~30 seconds.')) return
    setMsg(null)
    try {
      await api.del<void>(`/api/settings/mailboxes/${id}`)
      await load()
      setMsg({ kind: 'success', text: 'Mailbox deleted.' })
    } catch (e) {
      setMsg({ kind: 'danger', text: e instanceof Error ? e.message : 'Delete failed' })
    }
  }

  if (error) return <div className="alert alert-danger">{error}</div>
  if (!data) return <div className="text-muted">Loading…</div>

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }}>
      {msg && (
        <div className={`alert alert-${msg.kind} alert-dismissible`}>
          {msg.text}
          <button type="button" className="btn-close" onClick={() => setMsg(null)} />
        </div>
      )}

      <PageHeader
        title="Settings"
        subtitle="Mailboxes, AI endpoint, auto-send and prompts for this tenant"
      />

      {/* Mailboxes */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold">Mailboxes</div>
        <div className="card-body">
          <p className="text-muted small mb-3">
            Inboxes watched for incoming enquiries. Changes apply within ~30 seconds — no restart. To
            rotate a password or change server settings, use Edit — open tickets keep replying through
            the same mailbox (deleting and re-adding it would lose that link).
          </p>

          {!data.cipherConfigured && (
            <div className="alert alert-warning small">
              <strong>MAILBOX_ENCRYPTION_KEY is not set.</strong> Mailbox passwords cannot be encrypted,
              so mailboxes cannot be saved and no adapters will start. Generate a key with{' '}
              <code>openssl rand -base64 32</code> and set the environment variable.
            </div>
          )}

          {data.mailboxes.length > 0 ? (
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
                {data.mailboxes.map((m) => (
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
                    <td>
                      {!m.enabled ? (
                        <span className="badge bg-secondary">Disabled</span>
                      ) : m.loginOk === false ? (
                        <span
                          className="badge bg-danger"
                          title={`${m.loginError ?? 'Login failed'} — last good login: ${
                            m.lastLoginOkAt ? formatDateTime(m.lastLoginOkAt) : 'never'}. No new emails arrive from this mailbox.`}
                        >
                          Login failed
                        </span>
                      ) : m.running && m.loginOk ? (
                        <span className="badge bg-success" title={`Last login check OK: ${formatDateTime(m.lastLoginOkAt)}`}>
                          Connected
                        </span>
                      ) : m.running ? (
                        <span className="badge bg-success" title="Adapter registered — the first login check runs within ~5 min">
                          Running
                        </span>
                      ) : (
                        <span className="badge bg-warning text-dark" title="Adapter not registered yet — starts on the next reconcile pass">
                          Starting…
                        </span>
                      )}
                    </td>
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
                                    disabled={!data.cipherConfigured && editing.password !== ''}>
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
              <button type="submit" className="btn btn-primary btn-sm" disabled={!data.cipherConfigured}>
                Add mailbox
              </button>
              <span className="form-text ms-2">The password is stored encrypted (AES-256-GCM) and never displayed.</span>
            </div>
          </form>
        </div>
      </div>

      {/* AI Endpoint */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold">AI Endpoint</div>
        <div className="card-body">
          <p className="text-muted small mb-3">
            Where this tenant's LLM calls go. Leave blank to use the platform instance (
            <code>{data.platformOllamaUrl}</code>). Point it at your own Ollama server to keep prompts and
            drafts entirely on your infrastructure — each endpoint gets its own circuit breaker, so an
            outage on one never affects others.
          </p>
          <form
            className="row g-2"
            onSubmit={(e) => {
              e.preventDefault()
              void save(
                () => api.post<Settings>('/api/settings/ollama', { ollamaBaseUrl: ollamaUrl }),
                ollamaUrl.trim() ? `Ollama endpoint saved: ${ollamaUrl.trim()}`
                                 : 'Ollama endpoint override cleared — using the platform instance.',
              )
            }}
          >
            <div className="col-sm-8">
              <input type="text" className="form-control form-control-sm" placeholder={data.platformOllamaUrl}
                     value={ollamaUrl} onChange={(e) => setOllamaUrl(e.target.value)} />
            </div>
            <div className="col-sm-4">
              <button type="submit" className="btn btn-primary btn-sm">Save endpoint</button>
            </div>
          </form>
        </div>
      </div>

      {/* SMTP (read-only) */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold">Outgoing Mail (SMTP) — platform fallback</div>
        <div className="card-body">
          <p className="text-muted small mb-3">
            Replies are sent through this platform SMTP account unless a mailbox above has its own SMTP
            configured, in which case that tenant sends from its own address. These platform values come
            from environment variables.
          </p>
          <dl className="row mb-0">
            <dt className="col-sm-4">SMTP host</dt>
            <dd className="col-sm-8"><code>{data.smtpHost}</code></dd>
            <dt className="col-sm-4">SMTP port</dt>
            <dd className="col-sm-8"><code>{data.smtpPort}</code></dd>
            <dt className="col-sm-4">SMTP user</dt>
            <dd className="col-sm-8"><code>{data.smtpUser}</code></dd>
          </dl>
        </div>
      </div>

      {/* Notifications */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold">Notifications</div>
        <div className="card-body">
          <form
            onSubmit={(e) => {
              e.preventDefault()
              void save(
                () => api.post<Settings>('/api/settings/notifications', { draftEnabled }),
                `Notifications ${draftEnabled ? 'enabled' : 'disabled'}.`,
              )
            }}
          >
            <div className="form-check form-switch mb-3">
              <input className="form-check-input" type="checkbox" role="switch" id="draftEnabled"
                     checked={draftEnabled} onChange={(e) => setDraftEnabled(e.target.checked)} />
              <label className="form-check-label" htmlFor="draftEnabled">
                Send email notification to operator when AI draft is ready
              </label>
            </div>
            <p className="text-muted small">
              When enabled, a notification with the draft is sent to the operator's inbox after each AI
              processing. Disable if you prefer to check drafts directly in the UI.
            </p>
            <button type="submit" className="btn btn-primary btn-sm">Save</button>
          </form>
        </div>
      </div>

      {/* Auto-send */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold d-flex align-items-center gap-2">
          Auto-send
          <span className={`badge small ${data.autoSendEnabled ? 'bg-success' : 'bg-secondary'}`}>
            {data.autoSendEnabled ? 'ON' : 'OFF'}
          </span>
        </div>
        <div className="card-body">
          <p className="text-muted small mb-3">
            When the pipeline is confident enough in a draft, send it <strong>without operator review</strong>.
            Confidence is computed from verifiable facts (type classified, identifier found in the backend,
            rate matched) and is shown on every ticket — watch the scores for a while before switching this on.
          </p>
          <form
            onSubmit={(e) => {
              e.preventDefault()
              void save(
                () =>
                  api.post<Settings>('/api/settings/autosend', {
                    enabled: autoSendEnabled,
                    threshold: autoSendThreshold,
                    types: autoSendTypes,
                  }),
                'Auto-send settings saved.',
                (s) =>
                  s.autoSendEnabled && s.autoSendTypes.length === 0
                    ? { kind: 'warning', text: 'Auto-send is on but no ticket types are allowed — nothing will be sent automatically.' }
                    : { kind: 'success', text: `Auto-send settings saved${s.autoSendEnabled ? '' : ' (feature is off)'}.` },
              )
            }}
          >
            <div className="form-check form-switch mb-3">
              <input className="form-check-input" type="checkbox" role="switch" id="autoSendEnabled"
                     checked={autoSendEnabled} onChange={(e) => setAutoSendEnabled(e.target.checked)} />
              <label className="form-check-label" htmlFor="autoSendEnabled">
                Enable automatic sending (no human in the loop for matching tickets)
              </label>
            </div>
            <div className="mb-3">
              <label className="form-label fw-semibold" htmlFor="autoSendThreshold">Confidence threshold</label>
              <input type="number" className="form-control" style={{ maxWidth: 160 }} id="autoSendThreshold"
                     min={0} max={1} step={0.05}
                     value={autoSendThreshold} onChange={(e) => setAutoSendThreshold(e.target.value)} />
              <div className="form-text">
                0.0–1.0. A draft is auto-sent only when its confidence is at or above this value. Default 0.8 =
                identifier found in the backend AND a specific type assigned.
              </div>
            </div>
            <div className="mb-3">
              <span className="form-label fw-semibold d-block">Allowed ticket types</span>
              {data.allTicketTypes.map((t) => (
                <div className="form-check form-check-inline" key={t}>
                  <input className="form-check-input" type="checkbox" id={`autoType_${t}`}
                         checked={autoSendTypes.includes(t)} onChange={() => toggleType(t)} />
                  <label className="form-check-label small" htmlFor={`autoType_${t}`}>{t}</label>
                </div>
              ))}
              <div className="form-text">
                Only these types may be auto-sent. STATUS_UPDATE is the safe default — factual answers backed
                by TMS data. Think twice before adding RFQ: that sends prices without a human check.
              </div>
            </div>
            <button type="submit" className="btn btn-primary btn-sm">Save auto-send settings</button>
          </form>
        </div>
      </div>

      {/* Status Update Automation */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold d-flex align-items-center gap-2">
          Status Update Automation
          <span className={`badge small ${data.statusUpdateEnabled ? 'bg-success' : 'bg-secondary'}`}>
            {data.statusUpdateEnabled ? 'ON' : 'OFF'}
          </span>
        </div>
        <div className="card-body">
          <p className="text-muted small mb-3">
            Proactively email customers when their shipment reaches a milestone — no need for them to ask. A
            subscription is created automatically whenever a customer enquires about a shipment; updates are
            sent until it is delivered. Delivery reuses the <strong>auto-send</strong> gate above: turn
            auto-send on for STATUS_UPDATE to send these automatically, otherwise they appear as drafts for review.
          </p>
          <form
            onSubmit={(e) => {
              e.preventDefault()
              void save(
                () =>
                  api.post<Settings>('/api/settings/statusupdate', {
                    enabled: statusUpdateEnabled,
                    milestones: statusUpdateMilestones,
                  }),
                'Status-update automation saved.',
                (s) => ({ kind: 'success', text: `Status-update automation saved${s.statusUpdateEnabled ? '' : ' (feature is off)'}.` }),
              )
            }}
          >
            <div className="form-check form-switch mb-3">
              <input className="form-check-input" type="checkbox" role="switch" id="statusUpdateEnabled"
                     checked={statusUpdateEnabled} onChange={(e) => setStatusUpdateEnabled(e.target.checked)} />
              <label className="form-check-label" htmlFor="statusUpdateEnabled">
                Enable proactive status updates
              </label>
            </div>
            <div className="mb-3">
              <label className="form-label fw-semibold" htmlFor="statusUpdateMilestones">Milestone statuses</label>
              <input type="text" className="form-control" style={{ maxWidth: 360 }} id="statusUpdateMilestones"
                     value={statusUpdateMilestones} onChange={(e) => setStatusUpdateMilestones(e.target.value)} />
              <div className="form-text">
                Comma-separated backend status names that trigger a notification (e.g.{' '}
                <code>AT_PORT,RELEASED,DELIVERED</code>). Intermediate statuses stay silent so customers are
                not flooded.
              </div>
            </div>
            <button type="submit" className="btn btn-primary btn-sm">Save status-update settings</button>
          </form>
        </div>
      </div>

      {/* Quote Follow-up Automation */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold d-flex align-items-center gap-2">
          Quote Follow-up Automation
          <span className={`badge small ${data.quoteFollowUpEnabled ? 'bg-success' : 'bg-secondary'}`}>
            {data.quoteFollowUpEnabled ? 'ON' : 'OFF'}
          </span>
        </div>
        <div className="card-body">
          <p className="text-muted small mb-3">
            Chase silent quotes automatically. When a reply to an RFQ is sent, a one-shot follow-up is
            scheduled; if the customer has not been in touch after the delay below, a gentle "just following
            up on our quote" nudge is composed. Delivery reuses the <strong>auto-send</strong> gate above: RFQ
            is not auto-sent by default, so each nudge appears as a draft for review unless you allow-list RFQ
            for auto-send.
          </p>
          <form
            onSubmit={(e) => {
              e.preventDefault()
              void save(
                () =>
                  api.post<Settings>('/api/settings/quotefollowup', {
                    enabled: quoteFollowUpEnabled,
                    delayHours: quoteFollowUpDelayHours,
                  }),
                'Quote follow-up automation saved.',
                (s) => ({ kind: 'success', text: `Quote follow-up automation saved${s.quoteFollowUpEnabled ? '' : ' (feature is off)'}.` }),
              )
            }}
          >
            <div className="form-check form-switch mb-3">
              <input className="form-check-input" type="checkbox" role="switch" id="quoteFollowUpEnabled"
                     checked={quoteFollowUpEnabled} onChange={(e) => setQuoteFollowUpEnabled(e.target.checked)} />
              <label className="form-check-label" htmlFor="quoteFollowUpEnabled">
                Enable quote follow-ups
              </label>
            </div>
            <div className="mb-3">
              <label className="form-label fw-semibold" htmlFor="quoteFollowUpDelayHours">Delay (hours)</label>
              <input type="number" min={0} step={1} className="form-control" style={{ maxWidth: 200 }}
                     id="quoteFollowUpDelayHours"
                     value={quoteFollowUpDelayHours} onChange={(e) => setQuoteFollowUpDelayHours(e.target.value)} />
              <div className="form-text">
                Hours of customer silence after the quote was sent before a single nudge is sent (e.g.{' '}
                <code>48</code>). A reply from the customer cancels the nudge.
              </div>
            </div>
            <button type="submit" className="btn btn-primary btn-sm">Save quote follow-up settings</button>
          </form>
        </div>
      </div>

      {/* Smart Routing (Phase 3.10) */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold d-flex align-items-center gap-2">
          Smart Routing
          <span className={`badge small ${data.smartRoutingEnabled ? 'bg-success' : 'bg-secondary'}`}>
            {data.smartRoutingEnabled ? 'ON' : 'OFF'}
          </span>
        </div>
        <div className="card-body">
          <p className="text-muted small mb-3">
            The AI classifier also rates each incoming email's <strong>urgency</strong> and{' '}
            <strong>tone</strong> in the same step (no extra AI call). Urgent or negative emails get a
            tighter SLA window and float to the top of the queue. Optionally, auto-assign such escalated
            emails to a chosen operator — leave it blank to keep assignment manual.
          </p>
          <form
            onSubmit={(e) => {
              e.preventDefault()
              void save(
                () =>
                  api.post<Settings>('/api/settings/routing', {
                    smartRoutingEnabled,
                    escalationAssignee,
                  }),
                'Smart-routing settings saved.',
                (s) => ({ kind: 'success', text: `Smart-routing settings saved${s.smartRoutingEnabled ? '' : ' (feature is off)'}.` }),
              )
            }}
          >
            <div className="form-check form-switch mb-3">
              <input className="form-check-input" type="checkbox" role="switch" id="smartRoutingEnabled"
                     checked={smartRoutingEnabled} onChange={(e) => setSmartRoutingEnabled(e.target.checked)} />
              <label className="form-check-label" htmlFor="smartRoutingEnabled">
                Detect urgency &amp; sentiment
              </label>
            </div>
            <div className="mb-3">
              <label className="form-label fw-semibold" htmlFor="escalationAssignee">Escalation assignee</label>
              <select className="form-select" style={{ maxWidth: 360 }} id="escalationAssignee"
                      value={escalationAssignee} onChange={(e) => setEscalationAssignee(e.target.value)}>
                <option value="">— None (leave unassigned) —</option>
                {operators.map((op) => (
                  <option key={op.id} value={op.id}>{op.email}</option>
                ))}
              </select>
              <div className="form-text">
                Urgent or negative-tone emails are auto-assigned to this operator while still unclaimed.
                A manually assigned ticket is never reassigned.
              </div>
            </div>
            <button type="submit" className="btn btn-primary btn-sm">Save smart-routing settings</button>
          </form>
        </div>
      </div>

      {/* AI Prompts */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold">AI Prompts</div>
        <div className="card-body">
          <p className="text-muted small mb-3">
            Tune the prompts sent to the local LLM for each pipeline step. Changes take effect immediately —
            no restart needed. Use the placeholders below; they are filled in at request time:
          </p>
          <ul className="text-muted small mb-3">
            <li><code>{'{email}'}</code> — the customer's email body</li>
            <li><code>{'{itemContext}'}</code> — looked-up item data (drafting prompt only)</li>
            <li>
              <code>{'{rateContext}'}</code> — matched carrier rates with sell prices (drafting prompt only);
              without this placeholder matched rates are appended to the end of the prompt
            </li>
          </ul>
          <form
            onSubmit={(e) => {
              e.preventDefault()
              void save(
                () => api.post<Settings>('/api/settings/prompts', { extractionPrompt, draftingPrompt }),
                'AI prompts saved.',
              )
            }}
          >
            <div className="mb-3">
              <label className="form-label fw-semibold" htmlFor="extractionPrompt">Extraction prompt</label>
              <p className="text-muted small mb-2">Instructs the model to pull the key identifier out of the email.</p>
              <textarea className="form-control font-monospace small" id="extractionPrompt" rows={8}
                        value={extractionPrompt} onChange={(e) => setExtractionPrompt(e.target.value)} />
            </div>
            <div className="mb-3">
              <label className="form-label fw-semibold" htmlFor="draftingPrompt">Drafting prompt</label>
              <p className="text-muted small mb-2">Instructs the model to write the reply draft using the looked-up data.</p>
              <textarea className="form-control font-monospace small" id="draftingPrompt" rows={12}
                        value={draftingPrompt} onChange={(e) => setDraftingPrompt(e.target.value)} />
            </div>
            <button type="submit" className="btn btn-primary btn-sm">Save prompts</button>
          </form>
        </div>
      </div>

      {/* How to change env vars */}
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
    </div>
  )
}
