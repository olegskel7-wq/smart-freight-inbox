import { statusLabel } from '../../util/labels.ts'

// What the ticket list's URL asks for: the queue tabs, the Sort options, and how the query string
// maps to the /api/tickets request and the page title.

// Queue tabs — each maps to filters the API already supports (no invented backend filters).
// With no filter in the URL the page shows the Inbox (open work), as in the approved design.
export interface Queue { key: string; label: string; params: Record<string, string> }

export const QUEUES: Queue[] = [
  { key: 'inbox', label: 'Inbox', params: { view: 'open' } },
  { key: 'mine', label: 'Assigned to me', params: { assignee: 'mine' } },
  { key: 'autosent', label: 'Auto-sent', params: { status: 'AUTO_SENT' } },
  { key: 'closed', label: 'Closed', params: { status: 'CLOSED' } },
  // An unrecognised `view` falls back to the unfiltered list server-side.
  { key: 'all', label: 'All', params: { view: 'all' } },
]

// Explicit orderings for the Sort select; '' keeps the server default (escalated first, newest).
export const SORTS: { value: string; label: string }[] = [
  { value: '', label: 'Priority' },
  { value: 'createdAt:desc', label: 'Newest first' },
  { value: 'createdAt:asc', label: 'Oldest first' },
  { value: 'slaDeadlineAt:asc', label: 'SLA deadline' },
  { value: 'ticketType:asc', label: 'Type' },
  { value: 'status:asc', label: 'Status' },
  { value: 'senderEmail:asc', label: 'Sender' },
  { value: 'aiConfidence:desc', label: 'Confidence' },
]

// Human label for an active drill-down view (page title when no queue tab matches).
const VIEW_LABELS: Record<string, string> = {
  awaiting: 'Awaiting review',
  breached: 'SLA breached',
  autosent_today: 'Auto-sent today',
}

export interface ListQuery {
  /** Only set when the operator explicitly picks an ordering; otherwise the server applies its
   * default view (escalated-first, then newest). */
  sort: string | null
  dir: string
  status: string
  assignee: string
  /** The drill-down view sent to the server — the Inbox ('open') when the URL has no filter at all. */
  view: string
  /** The URL itself names a view — the filter selects then show All. */
  viewInUrl: boolean
  page: number
}

export function readListQuery(params: URLSearchParams): ListQuery {
  const status = params.get('status') ?? ''
  const assignee = params.get('assignee') ?? 'all'
  const viewInUrl = !!params.get('view')
  const unfiltered = !viewInUrl && !status && assignee === 'all'
  return {
    sort: params.get('sort'),
    dir: params.get('dir') ?? 'desc',
    status,
    assignee,
    view: unfiltered ? 'open' : (params.get('view') ?? ''),
    viewInUrl,
    page: Math.max(0, Number(params.get('page') ?? '0')),
  }
}

/** The /api/tickets request for a query. */
export function listRequest({ sort, dir, status, assignee, view, page }: Omit<ListQuery, 'viewInUrl'>): string {
  const qs = new URLSearchParams({ page: String(page) })
  if (sort !== null) {
    qs.set('sort', sort)
    qs.set('dir', dir)
  }
  // A drill-down view (queue tab or summary chip) takes precedence over the status/assignee filters.
  if (view) {
    qs.set('view', view)
  } else {
    if (status) qs.set('status', status)
    if (assignee !== 'all') qs.set('assignee', assignee)
  }
  return `/api/tickets?${qs.toString()}`
}

/** The queue tab the URL matches exactly, if any. */
export function activeQueueOf(params: URLSearchParams, { status, assignee, view }: ListQuery): Queue | undefined {
  return QUEUES.find((q) =>
    q.params.view ? q.params.view === view
      : !view && Object.entries(q.params).every(([k, v]) => params.get(k) === v)
        && (q.params.status ? assignee === 'all' : !status))
}

export function listTitle(activeQueue: Queue | undefined, { status, view }: ListQuery): string {
  return activeQueue?.label ?? VIEW_LABELS[view]
    ?? (status ? statusLabel(status) : 'Tickets')
}
