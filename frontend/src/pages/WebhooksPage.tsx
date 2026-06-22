import { Fragment, useCallback, useEffect, useState } from 'react'
import { useApi } from '../api/useApi.ts'
import PageHeader from '../components/PageHeader.tsx'
import { formatDateTime } from '../util/format.ts'
import type {
  CreateWebhookResponse,
  WebhookDelivery,
  WebhooksResponse,
} from '../types.ts'

type Msg = { kind: 'success' | 'danger'; text: string } | null

// React page for Phase 3.5 outbound webhooks: manage subscriptions (where SmartFreight POSTs signed
// domain events), test an endpoint, and inspect the delivery log. ADMIN-only (the nav link + route are
// gated; the API enforces the role regardless). Each mutation returns the refreshed snapshot.
export default function WebhooksPage() {
  const api = useApi()

  const [data, setData] = useState<WebhooksResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [msg, setMsg] = useState<Msg>(null)

  // New-subscription form.
  const [url, setUrl] = useState('')
  const [description, setDescription] = useState('')
  const [secret, setSecret] = useState('')
  const [allEvents, setAllEvents] = useState(true)
  const [selectedTypes, setSelectedTypes] = useState<string[]>([])

  // The freshly-created secret, shown once for the operator to copy.
  const [newSecret, setNewSecret] = useState<string | null>(null)

  // Per-subscription delivery log (subscriptionId → rows), loaded on expand.
  const [openLog, setOpenLog] = useState<number | null>(null)
  const [deliveries, setDeliveries] = useState<WebhookDelivery[]>([])

  const load = useCallback(async () => {
    setError(null)
    try {
      setData(await api.get<WebhooksResponse>('/api/webhooks'))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load webhooks')
    }
  }, [api])

  useEffect(() => {
    void load()
  }, [load])

  const toggleType = (type: string) => {
    setSelectedTypes((prev) =>
      prev.includes(type) ? prev.filter((t) => t !== type) : [...prev, type],
    )
  }

  const create = async (e: React.FormEvent) => {
    e.preventDefault()
    setMsg(null)
    setNewSecret(null)
    const eventTypes = allEvents ? ['*'] : selectedTypes
    try {
      const res = await api.post<CreateWebhookResponse>('/api/webhooks', {
        url,
        eventTypes,
        description: description || null,
        secret: secret || null,
      })
      setData(res.snapshot)
      setNewSecret(res.secret)
      setMsg({ kind: 'success', text: 'Webhook created. Copy the signing secret now — it is shown only once.' })
      setUrl('')
      setDescription('')
      setSecret('')
      setAllEvents(true)
      setSelectedTypes([])
    } catch (err) {
      setMsg({ kind: 'danger', text: err instanceof Error ? err.message : 'Failed to create webhook' })
    }
  }

  const mutate = async (call: () => Promise<WebhooksResponse>, ok: string) => {
    setMsg(null)
    try {
      setData(await call())
      setMsg({ kind: 'success', text: ok })
    } catch (e) {
      setMsg({ kind: 'danger', text: e instanceof Error ? e.message : 'Action failed' })
    }
  }

  const toggleEnabled = (sub: WebhooksResponse['subscriptions'][number]) =>
    mutate(
      () =>
        api.post<WebhooksResponse>(`/api/webhooks/${sub.id}`, {
          url: sub.url,
          eventTypes: sub.eventTypes,
          description: sub.description,
          enabled: !sub.enabled,
          secret: null,
        }),
      sub.enabled ? 'Webhook disabled.' : 'Webhook enabled.',
    )

  const remove = (id: number) => {
    if (!window.confirm('Delete this webhook and its delivery history?')) return
    void mutate(() => api.del<WebhooksResponse>(`/api/webhooks/${id}`), 'Webhook deleted.')
  }

  const sendTest = (id: number) =>
    mutate(() => api.post<WebhooksResponse>(`/api/webhooks/${id}/test`, {}), 'Test event queued.')

  const showLog = async (id: number) => {
    if (openLog === id) {
      setOpenLog(null)
      return
    }
    setOpenLog(id)
    try {
      setDeliveries(await api.get<WebhookDelivery[]>(`/api/webhooks/${id}/deliveries`))
    } catch {
      setDeliveries([])
    }
  }

  const deliveryBadge = (status: string) =>
    status === 'DELIVERED' ? 'text-bg-success' : status === 'DEAD' ? 'text-bg-danger' : 'text-bg-warning'

  if (error) return <div className="alert alert-danger">{error}</div>
  if (!data) return <div className="text-muted">Loading…</div>

  return (
    <div style={{ maxWidth: 960, margin: '0 auto' }}>
      {msg && (
        <div className={`alert alert-${msg.kind} alert-dismissible`}>
          {msg.text}
          <button type="button" className="btn-close" onClick={() => setMsg(null)} />
        </div>
      )}

      {newSecret && (
        <div className="alert alert-info">
          <p className="fw-semibold mb-1">Signing secret (shown once)</p>
          <p className="small mb-2">
            Store this in your receiver and verify the <code>X-SmartFreight-Signature</code> header. It cannot be
            retrieved later — only rotated.
          </p>
          <div className="d-flex gap-2 align-items-center">
            <code className="bg-light border rounded p-2 flex-grow-1 text-break">{newSecret}</code>
            <button
              type="button"
              className="btn btn-outline-secondary btn-sm text-nowrap"
              onClick={() => void navigator.clipboard?.writeText(newSecret)}
            >
              Copy
            </button>
            <button type="button" className="btn-close" onClick={() => setNewSecret(null)} />
          </div>
        </div>
      )}

      <PageHeader
        title="Webhooks"
        subtitle="Push SmartFreight events to your CRM, Zapier or any HTTPS endpoint — signed and retried"
      >
        {!data.secretConfigured && (
          <span className="badge text-bg-warning">
            Signing key not configured — set MAILBOX_ENCRYPTION_KEY to create webhooks
          </span>
        )}
      </PageHeader>

      {/* Add subscription */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold">Add webhook</div>
        <div className="card-body">
          <form onSubmit={create}>
            <div className="mb-3">
              <label className="form-label small fw-semibold">Endpoint URL (HTTPS)</label>
              <input
                type="url"
                className="form-control form-control-sm"
                placeholder="https://hooks.zapier.com/hooks/catch/…"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                required
              />
            </div>

            <div className="mb-3">
              <label className="form-label small fw-semibold">Events</label>
              <div className="form-check">
                <input
                  className="form-check-input"
                  type="checkbox"
                  id="wh-all"
                  checked={allEvents}
                  onChange={(e) => setAllEvents(e.target.checked)}
                />
                <label className="form-check-label" htmlFor="wh-all">
                  All events
                </label>
              </div>
              {!allEvents &&
                data.availableEventTypes.map((t) => (
                  <div className="form-check" key={t}>
                    <input
                      className="form-check-input"
                      type="checkbox"
                      id={`wh-${t}`}
                      checked={selectedTypes.includes(t)}
                      onChange={() => toggleType(t)}
                    />
                    <label className="form-check-label" htmlFor={`wh-${t}`}>
                      <code>{t}</code>
                    </label>
                  </div>
                ))}
            </div>

            <div className="mb-3">
              <label className="form-label small fw-semibold">Description (optional)</label>
              <input
                type="text"
                className="form-control form-control-sm"
                placeholder="e.g. HubSpot via Zapier"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </div>

            <div className="mb-3">
              <label className="form-label small fw-semibold">Signing secret (optional)</label>
              <input
                type="text"
                className="form-control form-control-sm"
                placeholder="Leave blank to generate a strong secret"
                value={secret}
                onChange={(e) => setSecret(e.target.value)}
              />
            </div>

            <button type="submit" className="btn btn-primary btn-sm" disabled={!data.secretConfigured}>
              Create webhook
            </button>
          </form>
        </div>
      </div>

      {/* Subscriptions */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold">Subscriptions ({data.subscriptions.length})</div>
        <div className="card-body p-0">
          {data.subscriptions.length === 0 ? (
            <p className="text-muted small p-3 mb-0">No webhooks yet. Add one above to push events out.</p>
          ) : (
            <table className="table table-sm align-middle mb-0">
              <thead className="table-light">
                <tr>
                  <th className="ps-3">Endpoint</th>
                  <th>Events</th>
                  <th>Status</th>
                  <th className="text-end pe-3"></th>
                </tr>
              </thead>
              <tbody>
                {data.subscriptions.map((sub) => (
                  <Fragment key={sub.id}>
                    <tr>
                      <td className="ps-3">
                        <div className="text-break">{sub.url}</div>
                        {sub.description && <div className="text-muted small">{sub.description}</div>}
                      </td>
                      <td>
                        {sub.eventTypes.map((t) => (
                          <span key={t} className="badge text-bg-light me-1">
                            {t === '*' ? 'all' : t}
                          </span>
                        ))}
                      </td>
                      <td>
                        <span className={`badge ${sub.enabled ? 'text-bg-success' : 'text-bg-secondary'}`}>
                          {sub.enabled ? 'Enabled' : 'Disabled'}
                        </span>
                      </td>
                      <td className="text-end pe-3 text-nowrap">
                        <button
                          type="button"
                          className="btn btn-outline-secondary btn-sm py-0 me-1"
                          onClick={() => void sendTest(sub.id)}
                        >
                          Test
                        </button>
                        <button
                          type="button"
                          className="btn btn-outline-secondary btn-sm py-0 me-1"
                          onClick={() => void showLog(sub.id)}
                        >
                          {openLog === sub.id ? 'Hide log' : 'Log'}
                        </button>
                        <button
                          type="button"
                          className="btn btn-outline-secondary btn-sm py-0 me-1"
                          onClick={() => void toggleEnabled(sub)}
                        >
                          {sub.enabled ? 'Disable' : 'Enable'}
                        </button>
                        <button
                          type="button"
                          className="btn btn-outline-danger btn-sm py-0"
                          onClick={() => remove(sub.id)}
                        >
                          ✕
                        </button>
                      </td>
                    </tr>
                    {openLog === sub.id && (
                      <tr>
                        <td colSpan={4} className="bg-light">
                          {deliveries.length === 0 ? (
                            <p className="text-muted small mb-0 p-2">No deliveries yet.</p>
                          ) : (
                            <table className="table table-sm mb-0 small">
                              <thead>
                                <tr>
                                  <th>Event</th>
                                  <th>Status</th>
                                  <th>Attempts</th>
                                  <th>HTTP</th>
                                  <th>Last error</th>
                                  <th>Created</th>
                                  <th>Delivered</th>
                                </tr>
                              </thead>
                              <tbody>
                                {deliveries.map((d) => (
                                  <tr key={d.id}>
                                    <td><code>{d.eventType}</code></td>
                                    <td>
                                      <span className={`badge ${deliveryBadge(d.status)}`}>{d.status}</span>
                                    </td>
                                    <td>{d.attempts}</td>
                                    <td>{d.responseStatus ?? '—'}</td>
                                    <td className="text-danger text-break">{d.lastError ?? ''}</td>
                                    <td>{formatDateTime(d.createdAt)}</td>
                                    <td>{formatDateTime(d.deliveredAt)}</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      <div className="card shadow-sm border-0 bg-light">
        <div className="card-body small text-muted">
          <p className="fw-semibold text-body mb-1">How webhooks work</p>
          When a subscribed event fires, SmartFreight POSTs a JSON envelope (<code>id</code>, <code>type</code>,{' '}
          <code>occurred_at</code>, <code>data</code>) to your URL with an{' '}
          <code>X-SmartFreight-Signature</code> header (HMAC-SHA256 of <code>timestamp.body</code>). Verify it with
          your signing secret. Delivery is at-least-once and retried with backoff — dedupe on the{' '}
          <code>X-SmartFreight-Delivery</code> id.
        </div>
      </div>
    </div>
  )
}
