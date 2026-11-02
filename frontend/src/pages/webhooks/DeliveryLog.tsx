import type { WebhookDelivery } from '../../types.ts'
import { formatDateTime } from '../../util/format.ts'

const deliveryBadge = (status: string) =>
  status === 'DELIVERED' ? 'text-bg-success' : status === 'DEAD' ? 'text-bg-danger' : 'text-bg-warning'

/** One subscription's delivery attempts, shown in a full-width row under it. */
export default function DeliveryLog({ deliveries }: { deliveries: WebhookDelivery[] }) {
  return (
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
  )
}
