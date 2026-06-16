import { useCallback, useEffect, useState } from 'react'
import { useApi } from '../api/useApi.ts'
import type { Settings } from '../types.ts'

type Msg = { kind: 'success' | 'danger' | 'warning'; text: string } | null

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
  const [extractionPrompt, setExtractionPrompt] = useState('')
  const [draftingPrompt, setDraftingPrompt] = useState('')

  // New-mailbox form.
  const [nbHost, setNbHost] = useState('')
  const [nbPort, setNbPort] = useState('993')
  const [nbUsername, setNbUsername] = useState('')
  const [nbPassword, setNbPassword] = useState('')
  const [nbSmtpHost, setNbSmtpHost] = useState('')
  const [nbSmtpPort, setNbSmtpPort] = useState('')
  const [nbFromAddress, setNbFromAddress] = useState('')

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
  // section add its own follow-up message (e.g. the auto-send "no types" warning).
  const save = async (call: () => Promise<Settings>, ok: string, after?: (s: Settings) => Msg) => {
    setMsg(null)
    try {
      const s = await call()
      applyData(s)
      setMsg(after?.(s) ?? { kind: 'success', text: ok })
    } catch (e) {
      setMsg({ kind: 'danger', text: e instanceof Error ? e.message : 'Save failed' })
    }
  }

  const toggleType = (t: string) =>
    setAutoSendTypes((prev) => (prev.includes(t) ? prev.filter((x) => x !== t) : [...prev, t]))

  const addMailbox = async (e: React.FormEvent) => {
    e.preventDefault()
    await save(
      () =>
        api.post<Settings>('/api/settings/mailboxes', {
          host: nbHost,
          port: nbPort ? Number(nbPort) : null,
          username: nbUsername,
          password: nbPassword,
          smtpHost: nbSmtpHost || null,
          smtpPort: nbSmtpPort ? Number(nbSmtpPort) : null,
          fromAddress: nbFromAddress || null,
        }),
      'Mailbox added — its IMAP adapter starts within ~30 seconds.',
    )
    setNbHost(''); setNbPort('993'); setNbUsername(''); setNbPassword('')
    setNbSmtpHost(''); setNbSmtpPort(''); setNbFromAddress('')
  }

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

      <h5 className="mb-4">Settings</h5>

      {/* Mailboxes */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold">Mailboxes</div>
        <div className="card-body">
          <p className="text-muted small mb-3">
            Inboxes watched for incoming enquiries. Changes apply within ~30 seconds — no restart. To
            rotate a password, delete the mailbox and add it again.
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
                  <tr key={m.id}>
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
                      ) : m.running ? (
                        <span className="badge bg-success">Running</span>
                      ) : (
                        <span className="badge bg-warning text-dark" title="Adapter not registered yet — starts on the next reconcile pass">
                          Starting…
                        </span>
                      )}
                    </td>
                    <td className="text-end">
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
                ))}
              </tbody>
            </table>
          ) : (
            <p className="text-muted small">No mailboxes yet — incoming mail is not being watched.</p>
          )}

          <hr />
          <p className="fw-semibold small mb-2">Add mailbox</p>
          <form onSubmit={addMailbox} className="row g-2">
            <div className="col-sm-4">
              <input type="text" className="form-control form-control-sm" placeholder="IMAP host (imap.gmail.com)"
                     value={nbHost} onChange={(e) => setNbHost(e.target.value)} required />
            </div>
            <div className="col-sm-2">
              <input type="number" className="form-control form-control-sm" min={1} max={65535}
                     value={nbPort} onChange={(e) => setNbPort(e.target.value)} required />
            </div>
            <div className="col-sm-3">
              <input type="text" className="form-control form-control-sm" placeholder="Username" autoComplete="off"
                     value={nbUsername} onChange={(e) => setNbUsername(e.target.value)} required />
            </div>
            <div className="col-sm-3">
              <input type="password" className="form-control form-control-sm" placeholder="Password" autoComplete="new-password"
                     value={nbPassword} onChange={(e) => setNbPassword(e.target.value)} required />
            </div>

            <div className="col-12">
              <p className="form-text mb-1 mt-1">
                Outgoing mail (optional). Leave blank to send replies through the platform account. When set,
                replies for this tenant are sent from this SMTP account, reusing the password above.
              </p>
            </div>
            <div className="col-sm-4">
              <input type="text" className="form-control form-control-sm" placeholder="SMTP host (smtp.gmail.com)"
                     value={nbSmtpHost} onChange={(e) => setNbSmtpHost(e.target.value)} />
            </div>
            <div className="col-sm-2">
              <input type="number" className="form-control form-control-sm" placeholder="587" min={1} max={65535}
                     value={nbSmtpPort} onChange={(e) => setNbSmtpPort(e.target.value)} />
            </div>
            <div className="col-sm-6">
              <input type="text" className="form-control form-control-sm" placeholder="From address (defaults to username)" autoComplete="off"
                     value={nbFromAddress} onChange={(e) => setNbFromAddress(e.target.value)} />
            </div>

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
