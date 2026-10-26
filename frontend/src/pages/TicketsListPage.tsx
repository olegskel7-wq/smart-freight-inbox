import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import { useApi } from '../api/useApi.ts'
import { useTicketStream } from '../api/useTicketStream.ts'
import type { Page, TicketStats, TicketSummary } from '../types.ts'
import { TICKET_STATUSES } from '../types.ts'
import { formatDateTime, formatDateTimeShort } from '../util/format.ts'
import { initialsOf, isDone, senderParts, statusLabel, toneLabel, typeLabel } from '../util/labels.ts'
import { groupTickets } from '../util/queue.ts'
import type { TicketQueue } from '../util/queue.ts'
import { useNow } from '../util/useNow.ts'

// Variant C (Phase 3.2 UI): a mail-style list grouped by SLA urgency. The server still decides the
// order (escalated first, then newest — or the operator's explicit sort); the page only buckets each
// row into Overdue / Due today / Later / Done, keeping server order inside a bucket. Grouping is per
// page (50 rows), which is what the operator is working through anyway.

// Queue tabs — each maps to filters the API already supports (no invented backend filters).
// With no filter in the URL the page shows the Inbox (open work), as in the approved design.
const QUEUES: { key: string; label: string; params: Record<string, string> }[] = [
  { key: 'inbox', label: 'Inbox', params: { view: 'open' } },
  { key: 'mine', label: 'Assigned to me', params: { assignee: 'mine' } },
  { key: 'autosent', label: 'Auto-sent', params: { status: 'AUTO_SENT' } },
  { key: 'closed', label: 'Closed', params: { status: 'CLOSED' } },
  // An unrecognised `view` falls back to the unfiltered list server-side.
  { key: 'all', label: 'All', params: { view: 'all' } },
]

// Explicit orderings for the Sort select; '' keeps the server default (escalated first, newest).
const SORTS: { value: string; label: string }[] = [
  { value: '', label: 'Priority' },
  { value: 'createdAt:desc', label: 'Newest first' },
  { value: 'createdAt:asc', label: 'Oldest first' },
  { value: 'slaDeadlineAt:asc', label: 'SLA deadline' },
  { value: 'ticketType:asc', label: 'Type' },
  { value: 'status:asc', label: 'Status' },
  { value: 'senderEmail:asc', label: 'Sender' },
  { value: 'aiConfidence:desc', label: 'Confidence' },
]

// Inline SVG glyphs for the summary chips (no icon-font dependency, no CDN).
const CHIP_ICONS = {
  open: ['M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z', 'M22 12h-6l-2 3h-4l-2-3H2'],
  review: ['M12 20h9', 'M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z'],
  breach: ['M12 8v4l2 2', 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z'],
  sent: ['M22 2 11 13', 'M22 2 15 22l-4-9-9-4z'],
}

function ChipIcon({ d }: { d: string[] }) {
  return (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
      strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {d.map((p) => <path key={p} d={p} />)}
    </svg>
  )
}

// A summary chip — a link that drills the list down to the tickets it counts (?view=…).
function StatChip(props: {
  view: string; active: boolean; tone: string; icon: string[]; value: number | null; label: string
}) {
  return (
    <div className="col-6 col-md-3">
      <Link
        to={`/tickets?view=${props.view}`}
        className={`sf-chip${props.active ? ' is-active' : ''}`}
        title={`Show ${props.label.toLowerCase()} tickets`}
      >
        <span className={`sf-chip-ic ${props.tone}`}><ChipIcon d={props.icon} /></span>
        <span className="sf-chip-body">
          <span className="sf-chip-value">{props.value ?? '—'}</span>
          <span className="sf-chip-label">{props.label}</span>
        </span>
      </Link>
    </div>
  )
}

// Human label for an active drill-down view (page title when no queue tab matches).
const VIEW_LABELS: Record<string, string> = {
  open: 'Inbox',
  awaiting: 'Awaiting review',
  breached: 'SLA breached',
  autosent_today: 'Auto-sent today',
}

export default function TicketsListPage() {
  const api = useApi()
  const auth = useAuth()
  const myId = auth.user?.profile.sub
  const location = useLocation()
  const [params, setParams] = useSearchParams()

  // `sort` is only sent when the operator explicitly picks an ordering; otherwise the server applies
  // its default view (escalated-first, then newest) — Phase 3.10.
  const sort = params.get('sort')
  const dir = params.get('dir') ?? 'desc'
  const status = params.get('status') ?? ''
  const assignee = params.get('assignee') ?? 'all'
  const unfiltered = !params.get('view') && !status && assignee === 'all'
  const view = unfiltered ? 'open' : (params.get('view') ?? '')
  const page = Math.max(0, Number(params.get('page') ?? '0'))

  const activeQueue = QUEUES.find((q) =>
    q.params.view ? q.params.view === view
      : !view && Object.entries(q.params).every(([k, v]) => params.get(k) === v)
        && (q.params.status ? assignee === 'all' : !status))?.key

  const [data, setData] = useState<Page<TicketSummary> | null>(null)
  const [stats, setStats] = useState<TicketStats | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [reprocessing, setReprocessing] = useState(false)
  const [acting, setActing] = useState<number | null>(null)
  const now = useNow()

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
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
      setData(await api.get<Page<TicketSummary>>(`/api/tickets?${qs.toString()}`))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load tickets')
    } finally {
      setLoading(false)
    }
  }, [api, sort, dir, status, assignee, view, page])

  // The summary strip is best-effort: a failed count never blocks the list.
  const loadStats = useCallback(async () => {
    try {
      setStats(await api.get<TicketStats>('/api/tickets/summary'))
    } catch {
      /* keep last known counts */
    }
  }, [api])

  useEffect(() => {
    void load()
  }, [load])
  useEffect(() => {
    void loadStats()
  }, [loadStats])

  // Live updates (Phase 3.2): the backend pushes a ticket-changed event per tenant; we re-fetch the
  // current query (and the summary counts). Refs keep the stream subscription stable across filter
  // changes (no reconnect just because a callback was recreated).
  const loadRef = useRef(load)
  const statsRef = useRef(loadStats)
  useEffect(() => {
    loadRef.current = load
  }, [load])
  useEffect(() => {
    statsRef.current = loadStats
  }, [loadStats])
  const onLiveEvent = useCallback(() => {
    void loadRef.current()
    void statsRef.current()
  }, [])
  const live = useTicketStream(onLiveEvent)

  const update = (next: Record<string, string>) => {
    // Start from the real address bar, not the router's copy: the OIDC sign-in callback strips its
    // one-time `code`/`state` with history.replaceState, which React Router never sees.
    const merged = new URLSearchParams(window.location.search)
    Object.entries(next).forEach(([k, v]) => (v ? merged.set(k, v) : merged.delete(k)))
    setParams(merged)
  }

  // Buckets in display order; the concatenation is the queue the ticket page walks with Send and next.
  const groups = useMemo(() => {
    return groupTickets(data?.content ?? [], now)
  }, [data, now])
  const queue: TicketQueue = {
    ids: groups.flatMap((g) => g.rows.map((r) => r.t.id)),
    back: location.pathname + location.search,
  }

  // Re-queue stuck/failed tickets through the AI pipeline (POST /api/admin/reprocess, OPERATOR-
  // gated, tenant-scoped). The endpoint returns a plain-text summary; the live stream refreshes
  // the list as tickets move.
  const reprocess = useCallback(async () => {
    setReprocessing(true)
    setError(null)
    setNotice(null)
    try {
      const result = await api.post<string>('/api/admin/reprocess')
      setNotice(typeof result === 'string' ? result : 'Reprocess queued.')
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Reprocess failed')
    } finally {
      setReprocessing(false)
    }
  }, [api, load])

  // Shared-queue assignment (Phase 3.3 S2): self-claim an unassigned ticket or release one of your
  // own. Assigning to another operator is a tenant-admin action, done from the ticket page.
  const assignment = async (id: number, action: 'assign' | 'unassign') => {
    setActing(id)
    setError(null)
    try {
      await api.post(`/api/tickets/${id}/${action}`, action === 'assign' ? {} : undefined)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : action === 'assign' ? 'Claim failed' : 'Release failed')
    } finally {
      setActing(null)
    }
  }

  const who = (t: TicketSummary) =>
    !t.assigneeUserId ? 'Unassigned'
      : t.assigneeUserId === myId ? 'You'
        : t.assigneeEmail ? senderParts(t.assigneeEmail).name : 'Assigned'

  const title = QUEUES.find((q) => q.key === activeQueue)?.label ?? VIEW_LABELS[view]
    ?? (status ? statusLabel(status) : 'Tickets')

  return (
    <div className="sf-ws">
      <div className="sf-pagehead">
        <div>
          <div className="d-flex align-items-center gap-2">
            <h1 className="h4 mb-0">{title}</h1>
            <span
              className={`badge rounded-pill ${live ? 'bg-success' : 'bg-secondary'}`}
              title={live ? 'Live updates connected' : 'Live updates disconnected'}
            >
              {live ? '● Live' : '○ Offline'}
            </span>
          </div>
          <p className="sf-pagesub">
            {stats ? `${stats.open} to review · ${stats.slaBreached} overdue` : 'Incoming customer enquiries — triaged, looked up and drafted by AI'}
          </p>
        </div>
        <button
          type="button"
          className="btn btn-outline-primary btn-sm ms-auto"
          onClick={() => void reprocess()}
          disabled={reprocessing}
          title="Re-queue stuck/failed tickets through the AI pipeline"
        >
          {reprocessing ? 'Reprocessing…' : 'Reprocess stuck'}
        </button>
      </div>

      <nav className="sf-tabs" aria-label="Queues">
        {QUEUES.map((q) => (
          <Link
            key={q.key}
            to={`/tickets?${new URLSearchParams(q.params).toString()}`}
            className={`sf-tab${activeQueue === q.key ? ' is-active' : ''}`}
            aria-current={activeQueue === q.key ? 'page' : undefined}
          >
            {q.label}
          </Link>
        ))}
      </nav>

      <div className="row g-3 mb-3">
        <StatChip view="open" active={view === 'open'} tone="sf-ic-blue" icon={CHIP_ICONS.open} value={stats?.open ?? null} label="Open" />
        <StatChip view="awaiting" active={view === 'awaiting'} tone="sf-ic-amber" icon={CHIP_ICONS.review} value={stats?.awaitingReview ?? null} label="Awaiting review" />
        <StatChip view="breached" active={view === 'breached'} tone="sf-ic-red" icon={CHIP_ICONS.breach} value={stats?.slaBreached ?? null} label="SLA breached" />
        <StatChip view="autosent_today" active={view === 'autosent_today'} tone="sf-ic-teal" icon={CHIP_ICONS.sent} value={stats?.autoSentToday ?? null} label="Auto-sent today" />
      </div>

      <div className="sf-filters">
        {data && <span className="sf-count">{data.totalElements} tickets</span>}
        <span className="ms-auto" />
        <label className="text-muted small mb-0" htmlFor="f-assignee">Assignee</label>
        <select
          id="f-assignee"
          className="form-select form-select-sm"
          value={params.get('view') ? 'all' : assignee}
          onChange={(e) => update({ assignee: e.target.value === 'all' ? '' : e.target.value, view: e.target.value === 'all' && !status ? 'all' : '', page: '0' })}
        >
          <option value="all">All</option>
          <option value="mine">Mine</option>
          <option value="unassigned">Unassigned</option>
        </select>
        <label className="text-muted small mb-0" htmlFor="f-status">Status</label>
        <select
          id="f-status"
          className="form-select form-select-sm"
          value={params.get('view') ? '' : status}
          onChange={(e) => update({ status: e.target.value, view: !e.target.value && assignee === 'all' ? 'all' : '', page: '0' })}
        >
          <option value="">All</option>
          {TICKET_STATUSES.map((s) => (
            <option key={s} value={s}>{statusLabel(s)}</option>
          ))}
        </select>
        <label className="text-muted small mb-0" htmlFor="f-sort">Sort</label>
        <select
          id="f-sort"
          className="form-select form-select-sm"
          value={sort ? `${sort}:${dir}` : ''}
          onChange={(e) => {
            const [s, d] = e.target.value.split(':')
            update({ sort: s ?? '', dir: d ?? '', page: '0' })
          }}
        >
          {SORTS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
        </select>
      </div>

      {notice && (
        <div className="alert alert-info alert-dismissible">
          {notice}
          <button type="button" className="btn-close" aria-label="Dismiss" onClick={() => setNotice(null)} />
        </div>
      )}
      {error && <div className="alert alert-danger">{error}</div>}

      {groups.map((g) => (
        <section key={g.key} className="sf-group-sec" aria-label={g.title}>
          <h2 className={`sf-group-title is-${g.key}`}>{g.title} <span>{g.rows.length}</span></h2>
          <div className="sf-list">
            {g.rows.map(({ t, sla }) => {
              const sender = senderParts(t.senderEmail)
              const tone = toneLabel(t.urgency, t.sentimentNegative)
              return (
                <div key={t.id} className="sf-row">
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
                      {who(t)} · {statusLabel(t.status).toLowerCase()}
                    </span>
                    {!t.assigneeUserId && !isDone(t.status) && (
                      <button type="button" className="sf-row-action" disabled={acting === t.id} onClick={() => void assignment(t.id, 'assign')}>
                        Claim
                      </button>
                    )}
                    {t.assigneeUserId === myId && (
                      <button type="button" className="sf-row-action" disabled={acting === t.id} onClick={() => void assignment(t.id, 'unassign')}>
                        Release
                      </button>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </section>
      ))}

      {!loading && data?.content.length === 0 && (
        <div className="sf-list text-center text-muted py-5">Nothing here — the queue is clear.</div>
      )}
      {loading && !data && <div className="text-muted mt-2">Loading…</div>}

      {data && data.totalPages > 1 && (
        <nav className="d-flex justify-content-between align-items-center mt-3" aria-label="Pages">
          <span className="text-muted small">
            Page {data.page + 1} of {data.totalPages} · {data.totalElements} tickets
          </span>
          <div className="btn-group">
            <button
              type="button"
              className="btn btn-outline-secondary btn-sm"
              disabled={data.page <= 0}
              onClick={() => update({ page: String(page - 1) })}
            >
              Previous
            </button>
            <button
              type="button"
              className="btn btn-outline-secondary btn-sm"
              disabled={data.page >= data.totalPages - 1}
              onClick={() => update({ page: String(page + 1) })}
            >
              Next
            </button>
          </div>
        </nav>
      )}
    </div>
  )
}
