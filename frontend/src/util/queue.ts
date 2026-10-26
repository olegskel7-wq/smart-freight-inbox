// "Send and next" plumbing (Phase 3.2 UI, variant C). The list hands the ticket page the ids of the
// queue it was opened from, in display order, via router location state — so prev/next and
// Send-and-next walk exactly what the operator was looking at. History state survives a reload;
// a deep link without it falls back to the Inbox queue (see TicketDetailPage).

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
