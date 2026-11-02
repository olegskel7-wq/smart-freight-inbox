import type { WebhookSubscription } from '../../types.ts'

/**
 * One subscription: endpoint and description, its event types ("all" for `*`), Enabled / Disabled /
 * Auto-disabled with the reason, and Test, Log, Enable or Disable, and Delete.
 */
export default function SubscriptionRow({ sub, logOpen, onTest, onToggleLog, onToggleEnabled, onDelete }: {
  sub: WebhookSubscription
  /** This row's delivery log is shown under it. */
  logOpen: boolean
  onTest: () => void
  onToggleLog: () => void
  onToggleEnabled: () => void
  onDelete: () => void
}) {
  return (
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
        {sub.enabled ? (
          <span className="badge text-bg-success">Enabled</span>
        ) : sub.disabledReason ? (
          <>
            <span className="badge text-bg-warning">Auto-disabled</span>
            <div className="text-muted small mt-1">{sub.disabledReason}</div>
          </>
        ) : (
          <span className="badge text-bg-secondary">Disabled</span>
        )}
      </td>
      <td className="text-end pe-3 text-nowrap">
        <button type="button" className="btn btn-outline-secondary btn-sm py-0 me-1" onClick={onTest}>
          Test
        </button>
        <button type="button" className="btn btn-outline-secondary btn-sm py-0 me-1" onClick={onToggleLog}>
          {logOpen ? 'Hide log' : 'Log'}
        </button>
        <button type="button" className="btn btn-outline-secondary btn-sm py-0 me-1" onClick={onToggleEnabled}>
          {sub.enabled ? 'Disable' : 'Enable'}
        </button>
        <button type="button" className="btn btn-outline-danger btn-sm py-0" onClick={onDelete}>
          ✕
        </button>
      </td>
    </tr>
  )
}
