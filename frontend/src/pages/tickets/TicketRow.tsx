import { Link } from 'react-router-dom'
import type { TicketSummary } from '../../types.ts'
import { formatDateTime, formatDateTimeShort } from '../../util/format.ts'
import type { SlaInfo } from '../../util/format.ts'
import { initialsOf, isDone, senderParts, statusLabel, toneLabel, typeLabel } from '../../util/labels.ts'
import type { TicketQueue } from '../../util/queue.ts'

export type Assignment = 'assign' | 'unassign'

/**
 * One mail-style row: sender, type and tone badges, the subject linking to the ticket (handing it
 * the queue for Send and next), the SLA — or the last update once the ticket is done — and who has
 * it, with Claim on an open unassigned ticket and Release on your own.
 */
export default function TicketRow({ t, sla, queue, myId, busy, onAssignment }: {
  t: TicketSummary
  sla: SlaInfo | null
  queue: TicketQueue
  myId: string | undefined
  /** A claim or release of this row is in flight. */
  busy: boolean
  onAssignment: (id: number, action: Assignment) => void
}) {
  const sender = senderParts(t.senderEmail)
  const tone = toneLabel(t.urgency, t.sentimentNegative)
  const who = !t.assigneeUserId ? 'Unassigned'
    : t.assigneeUserId === myId ? 'You'
      : t.assigneeEmail ? senderParts(t.assigneeEmail).name : 'Assigned'
  return (
    <div className="sf-row">
      <span className="sf-av" aria-hidden="true">{initialsOf(sender.name)}</span>
      <div className="sf-row-main">
        <div className="sf-row-top">
          <span className="sf-row-name" title={sender.address}>{sender.name}</span>
          {sender.org && <span className="sf-row-org">{sender.org}</span>}
          <span className="sf-type">{typeLabel(t.ticketType)}</span>
          {tone && <span className="sf-tone">{tone}</span>}
          {t.proactive && <span className="sf-type" title="Outbound update started by SmartFreight">Proactive</span>}
        </div>
        <Link to={`/tickets/${t.id}`} state={{ queue }} className="sf-row-subject stretched-link" title={t.subject || undefined}>
          {t.subject || '(no subject)'}
        </Link>
        {t.snippet && <div className="sf-row-snippet">{t.snippet}</div>}
      </div>
      <div className="sf-row-side">
        {sla ? (
          <span className={`sf-sla is-${sla.tone}`} title={`SLA deadline ${formatDateTime(t.slaDeadlineAt)}`}>{sla.text}</span>
        ) : (
          <span className="sf-sla is-muted" title={formatDateTime(t.updatedAt)}>{formatDateTimeShort(t.updatedAt)}</span>
        )}
        <span className="sf-row-who" title={t.assigneeEmail ?? undefined}>
          {who} · {statusLabel(t.status).toLowerCase()}
        </span>
        {!t.assigneeUserId && !isDone(t.status) && (
          <button type="button" className="sf-row-action" disabled={busy} onClick={() => onAssignment(t.id, 'assign')}>
            Claim
          </button>
        )}
        {t.assigneeUserId === myId && (
          <button type="button" className="sf-row-action" disabled={busy} onClick={() => onAssignment(t.id, 'unassign')}>
            Release
          </button>
        )}
      </div>
    </div>
  )
}
