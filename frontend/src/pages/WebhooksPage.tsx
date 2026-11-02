import { Fragment, useCallback, useState } from 'react'
import { useApi } from '../api/useApi.ts'
import { usePageData } from '../api/usePageData.ts'
import PageHeader from '../components/PageHeader.tsx'
import type {
  CreateWebhookResponse,
  WebhookDelivery,
  WebhookSubscription,
  WebhooksResponse,
} from '../types.ts'
import { errorMessage } from '../util/errors.ts'
import DeliveryLog from './webhooks/DeliveryLog.tsx'
import SigningSecretAlert from './webhooks/SigningSecretAlert.tsx'
import SubscriptionRow from './webhooks/SubscriptionRow.tsx'
import WebhookForm from './webhooks/WebhookForm.tsx'
import type { NewWebhook } from './webhooks/WebhookForm.tsx'
import WebhooksHowToCard from './webhooks/WebhooksHowToCard.tsx'

type Msg = { kind: 'success' | 'danger'; text: string } | null

// Outbound webhooks: manage subscriptions (where SmartFreight POSTs signed
// domain events), test an endpoint, and inspect the delivery log. ADMIN-only (the nav link + route are
// gated; the API enforces the role regardless). Each mutation returns the refreshed snapshot.
export default function WebhooksPage() {
  const api = useApi()

  const [msg, setMsg] = useState<Msg>(null)

  // The freshly-created secret, shown once for the operator to copy.
  const [newSecret, setNewSecret] = useState<string | null>(null)

  // The one subscription whose delivery log is open, and its rows (loaded on every open).
  const [openLog, setOpenLog] = useState<number | null>(null)
  const [deliveries, setDeliveries] = useState<WebhookDelivery[]>([])

  const load = useCallback(() => api.get<WebhooksResponse>('/api/webhooks'), [api])
  const { data, setData, error } = usePageData(load, 'Failed to load webhooks')

  const create = async (webhook: NewWebhook) => {
    setMsg(null)
    setNewSecret(null)
    try {
      const res = await api.post<CreateWebhookResponse>('/api/webhooks', webhook)
      setData(res.snapshot)
      setNewSecret(res.secret)
      setMsg({ kind: 'success', text: 'Webhook created. Copy the signing secret now — it is shown only once.' })
      return true
    } catch (err) {
      setMsg({ kind: 'danger', text: errorMessage(err, 'Failed to create webhook') })
      return false
    }
  }

  const mutate = async (call: () => Promise<WebhooksResponse>, ok: string) => {
    setMsg(null)
    try {
      setData(await call())
      setMsg({ kind: 'success', text: ok })
    } catch (e) {
      setMsg({ kind: 'danger', text: errorMessage(e, 'Action failed') })
    }
  }

  const toggleEnabled = (sub: WebhookSubscription) =>
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

      {newSecret && <SigningSecretAlert secret={newSecret} onClose={() => setNewSecret(null)} />}

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

      <WebhookForm
        availableEventTypes={data.availableEventTypes}
        secretConfigured={data.secretConfigured}
        onCreate={create}
      />

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
                    <SubscriptionRow
                      sub={sub}
                      logOpen={openLog === sub.id}
                      onTest={() => void sendTest(sub.id)}
                      onToggleLog={() => void showLog(sub.id)}
                      onToggleEnabled={() => void toggleEnabled(sub)}
                      onDelete={() => remove(sub.id)}
                    />
                    {openLog === sub.id && <DeliveryLog deliveries={deliveries} />}
                  </Fragment>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      <WebhooksHowToCard />
    </div>
  )
}
