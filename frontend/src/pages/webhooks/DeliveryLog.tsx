import { useCallback } from 'react'
import { useApi } from '../../api/useApi.ts'
import { usePageData } from '../../api/usePageData.ts'
import type { WebhookDelivery } from '../../types.ts'
import { formatDateTime } from '../../util/format.ts'

const deliveryBadge = (status: string) =>
  status === 'DELIVERED' ? 'text-bg-success' : status === 'DEAD' ? 'text-bg-danger' : 'text-bg-warning'

/**
 * One subscription's delivery attempts, shown in a full-width row under it. Mounted only while the
 * log is open, so every open loads afresh and an answer for a log closed meanwhile is dropped; a
 * failed load reads as an empty log. A new `refreshKey` (a test queued for this webhook) reloads it.
 */
export default function DeliveryLog({ subscriptionId, refreshKey }: {
  subscriptionId: number
  refreshKey: number
}) {
  const api = useApi()
  const load = useCallback(
    () => {
      void refreshKey // a dependency on purpose: a new key is a new load, which usePageData runs
      return api
        .get<WebhookDelivery[]>(`/api/webhooks/${subscriptionId}/deliveries`)
        .catch((): WebhookDelivery[] => [])
    },
    [api, subscriptionId, refreshKey],
  )
  const { data: deliveries } = usePageData(load, 'Failed to load deliveries')

  return (
    <tr>
      <td colSpan={4} className="bg-light">
        {deliveries === null ? (
          <p className="text-muted small mb-0 p-2">Loading…</p>
        ) : deliveries.length === 0 ? (
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
  )
}
