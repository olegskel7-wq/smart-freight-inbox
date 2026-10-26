// "Send and next" plumbing (Phase 3.2 UI, variant C). The list hands the ticket page the ids of the
// queue it was opened from, in display order, via router location state — so prev/next and
// Send-and-next walk exactly what the operator was looking at. History state survives a reload;
// a deep link without it falls back to the Inbox queue (see TicketDetailPage), grouped the same way.
import type { TicketSummary } from '../types.ts'
import { slaInfo } from './format.ts'
import type { SlaInfo } from './format.ts'
import { isDone } from './labels.ts'

export interface TicketQueue {
  /** Ticket ids in the order the list showed them. */
  ids: number[]
  /** The list URL (path + query) to return to. */
  back: string
}

export function queueFromState(state: unknown): TicketQueue | null {
  const q = (state as { queue?: TicketQueue } | null)?.queue
  return q && Array.isArray(q.ids) && typeof q.back === 'string' ? q : null
}

export type GroupKey = SlaInfo['bucket'] | 'done'

const GROUPS: { key: GroupKey; title: string }[] = [
  { key: 'overdue', title: 'Overdue' },
  { key: 'today', title: 'Due today' },
  { key: 'later', title: 'Later' },
  { key: 'done', title: 'Done' },
]

export interface TicketRow {
  t: TicketSummary
  /** Null for finished tickets (no SLA to show). */
  sla: SlaInfo | null
}

/**
 * Buckets a page of tickets into Overdue / Due today / Later / Done, keeping the server's order
 * inside each bucket; empty buckets are dropped. The concatenation is the Send-and-next order.
 */
export function groupTickets(tickets: TicketSummary[], now: number) {
  const rows: TicketRow[] = tickets.map((t) => ({
    t,
    sla: isDone(t.status) ? null : slaInfo(t.slaDeadlineAt, t.slaBreached, now),
  }))
  return GROUPS
    .map((g) => ({ ...g, rows: rows.filter((r) => (r.sla?.bucket ?? 'done') === g.key) }))
    .filter((g) => g.rows.length > 0)
}
