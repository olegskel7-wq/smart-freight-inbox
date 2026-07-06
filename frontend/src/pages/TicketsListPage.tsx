import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import { useApi } from '../api/useApi.ts'
import { useTicketStream } from '../api/useTicketStream.ts'
import type { Page, TicketStats, TicketSummary } from '../types.ts'
import { TICKET_STATUSES } from '../types.ts'
import { formatConfidence, formatDateTime, formatDateTimeShort, statusBadgeClass } from '../util/format.ts'

// Fixed-layout column plan: px widths pin the compact columns, Subject (no width)
// absorbs the leftover — the table can never outgrow the card (no horizontal scroll).
// `cls: 'sf-col-wide'` columns are hidden on narrow laptops (<1300px) in favour of Subject.
const COLUMNS: { key: string; label: string; sortable: boolean; width?: number; cls?: string }[] = [
  { key: 'id', label: 'ID', sortable: true, width: 52 },
  { key: 'senderEmail', label: 'From', sortable: true, width: 150 },
  { key: 'subject', label: 'Subject', sortable: true },
  { key: 'ticketType', label: 'Type', sortable: true, width: 105 },
  { key: 'extractedIdentifier', label: 'Identifier', sortable: true, width: 100, cls: 'sf-col-wide' },
  { key: 'status', label: 'Status', sortable: true, width: 110 },
  { key: 'aiConfidence', label: 'Conf.', sortable: true, width: 56, cls: 'sf-col-wide' },
  { key: 'assignee', label: 'Assignee', sortable: false, width: 120 },
  { key: 'slaDeadlineAt', label: 'SLA', sortable: true, width: 96 },
  { key: 'createdAt', label: 'Received', sortable: true, width: 96 },
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

// Human label for an active drill-down view (shown as a clearable pill in the card header).
const VIEW_LABELS: Record<string, string> = {
  open: 'Open',
  awaiting: 'Awaiting review',
  breached: 'SLA breached',
  autosent_today: 'Auto-sent today',
}

function initials(email: string | null): string {
  if (!email) return '?'
  return email.replace(/@.*/, '').slice(0, 2).toUpperCase()
}

export default function TicketsListPage() {
  const api = useApi()
  const auth = useAuth()
  const myId = auth.user?.profile.sub
  const [params, setParams] = useSearchParams()

  // `sort` is only sent when the operator explicitly picks a column; otherwise the server applies its
  // default view (escalated-first, then newest). Distinguishing "no preference" from "clicked Received"
  // is why `sort` stays null until a column is clicked, and every reader guards on `explicitSort` (Phase 3.10).
  const sort = params.get('sort')
  const explicitSort = sort !== null
  const dir = params.get('dir') ?? 'desc'
  const status = params.get('status') ?? ''
  const assignee = params.get('assignee') ?? 'all'
  const view = params.get('view') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0'))

  const [data, setData] = useState<Page<TicketSummary> | null>(null)
  const [stats, setStats] = useState<TicketStats | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [reprocessing, setReprocessing] = useState(false)
  const [acting, setActing] = useState<number | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const qs = new URLSearchParams({ page: String(page) })
      // Omit sort/dir unless the operator chose a column, so the server keeps escalated tickets on top.
      if (sort !== null) {
        qs.set('sort', sort)
        qs.set('dir', dir)
      }
      // A drill-down view (from a summary chip) takes precedence over the status/assignee filters.
      if (view) {
        qs.set('view', view)
      } else {
        if (status) qs.set('status', status)
        if (assignee && assignee !== 'all') qs.set('assignee', assignee)
      }
      const result = await api.get<Page<TicketSummary>>(`/api/tickets?${qs.toString()}`)
      setData(result)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load tickets')
    } finally {
      setLoading(false)
    }
  }, [api, explicitSort, sort, dir, status, assignee, view, page])

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
  // current query (and the summary counts) so both always reflect the active filter/sort/page. Refs
  // keep the stream subscription stable across filter changes (no reconnect just because a callback
  // was recreated).
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
    const merged = new URLSearchParams(params)
    Object.entries(next).forEach(([k, v]) => (v ? merged.set(k, v) : merged.delete(k)))
    setParams(merged)
  }

  const toggleSort = (key: string) => {
    // First click on a column (no explicit sort yet) sets desc; clicking the active column flips it.
    const nextDir = explicitSort && sort === key && dir === 'desc' ? 'asc' : 'desc'
    update({ sort: key, dir: nextDir, page: '0' })
  }

  // Re-queue stuck/failed tickets through the AI pipeline (POST /api/admin/reprocess, OPERATOR-
  // gated, tenant-scoped). Used after Ollama recovers or an operator rejected a draft. The
  // endpoint returns a plain-text summary; the live stream refreshes the list as tickets move.
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
  // own. Assigning to another operator is a tenant-admin action, done from the ticket detail page.
  const claim = async (id: number) => {
    setActing(id)
    setError(null)
    try {
      await api.post(`/api/tickets/${id}/assign`, {})
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Claim failed')
    } finally {
      setActing(null)
    }
  }

  const release = async (id: number) => {
    setActing(id)
    setError(null)
    try {
      await api.post(`/api/tickets/${id}/unassign`)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Release failed')
    } finally {
      setActing(null)
    }
  }

  return (
    <div>
      <div className="sf-pagehead">
        <div>
          <div className="d-flex align-items-center gap-2">
            <h1 className="h4 mb-0">Tickets</h1>
            <span
              className={`badge rounded-pill ${live ? 'bg-success' : 'bg-secondary'}`}
              title={live ? 'Live updates connected' : 'Live updates disconnected'}
            >
              {live ? '● Live' : '○ Offline'}
            </span>
          </div>
          <p className="sf-pagesub">Incoming customer enquiries — triaged, looked up and drafted by AI</p>
        </div>
      </div>

      <div className="row g-3 mb-3">
        <StatChip view="open" active={view === 'open'} tone="sf-ic-blue" icon={CHIP_ICONS.open} value={stats?.open ?? null} label="Open" />
        <StatChip view="awaiting" active={view === 'awaiting'} tone="sf-ic-amber" icon={CHIP_ICONS.review} value={stats?.awaitingReview ?? null} label="Awaiting review" />
        <StatChip view="breached" active={view === 'breached'} tone="sf-ic-red" icon={CHIP_ICONS.breach} value={stats?.slaBreached ?? null} label="SLA breached" />
        <StatChip view="autosent_today" active={view === 'autosent_today'} tone="sf-ic-teal" icon={CHIP_ICONS.sent} value={stats?.autoSentToday ?? null} label="Auto-sent today" />
      </div>

      {notice && (
        <div className="alert alert-info alert-dismissible">
          {notice}
          <button type="button" className="btn-close" onClick={() => setNotice(null)} />
        </div>
      )}
      {error && <div className="alert alert-danger">{error}</div>}

      <div className="card shadow-sm">
        <div className="card-header d-flex align-items-center gap-2 flex-wrap">
          <span className="fw-semibold">{view ? VIEW_LABELS[view] ?? 'Tickets' : 'All tickets'}</span>
          {data && <span className="sf-count">{data.totalElements}</span>}
          {view && (
            <Link to="/tickets" className="sf-clear" title="Clear filter">Clear ✕</Link>
          )}
          <div className="ms-auto d-flex align-items-center gap-2 flex-wrap">
            <button
              type="button"
              className="btn btn-outline-primary btn-sm"
              onClick={() => void reprocess()}
              disabled={reprocessing}
              title="Re-queue stuck/failed tickets through the AI pipeline"
            >
              {reprocessing ? 'Reprocessing…' : 'Reprocess stuck'}
            </button>
            <label className="text-muted small mb-0">Assignee</label>
            <select
              className="form-select form-select-sm"
              style={{ width: 'auto' }}
              value={view ? 'all' : assignee}
              onChange={(e) => update({ assignee: e.target.value === 'all' ? '' : e.target.value, view: '', page: '0' })}
            >
              <option value="all">All</option>
              <option value="mine">Mine</option>
              <option value="unassigned">Unassigned</option>
            </select>
            <label className="text-muted small mb-0">Status</label>
            <select
              className="form-select form-select-sm"
              style={{ width: 'auto' }}
              value={view ? '' : status}
              onChange={(e) => update({ status: e.target.value, view: '', page: '0' })}
            >
              <option value="">All</option>
              {TICKET_STATUSES.map((s) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
          </div>
        </div>

        <div className="table-responsive">
          <table className="table table-hover align-middle mb-0 sf-table-fixed">
            <thead>
              <tr>
                {COLUMNS.map((c) => (
                  <th
                    key={c.key}
                    role={c.sortable ? 'button' : undefined}
                    onClick={c.sortable ? () => toggleSort(c.key) : undefined}
                    className={'user-select-none' + (c.cls ? ` ${c.cls}` : '')}
                    style={c.width ? { width: c.width } : undefined}
                  >
                    {c.label}
                    {explicitSort && sort === c.key && <span className="ms-1">{dir === 'asc' ? '▲' : '▼'}</span>}
                  </th>
                ))}
                <th className="text-end" style={{ width: 88 }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {data?.content.map((t) => (
                <tr key={t.id}>
                  <td><Link to={`/tickets/${t.id}`}>{t.id}</Link></td>
                  <td title={t.senderEmail ?? undefined}>{t.senderEmail ?? '—'}</td>
                  <td title={t.subject || undefined}>
                    <Link to={`/tickets/${t.id}`}>{t.subject || '(no subject)'}</Link>
                  </td>
                  <td className="sf-cell-wrap">
                    {t.ticketType ? <span className="sf-type">{t.ticketType}</span> : <span className="text-muted">—</span>}
                    {t.urgency === 'HIGH' && <span className="badge bg-warning text-dark ms-1" title="High urgency">Urgent</span>}
                    {t.sentimentNegative && <span className="badge bg-danger ms-1" title="Negative tone">Angry</span>}
                  </td>
                  <td className="sf-col-wide" title={t.extractedIdentifier ?? undefined}>{t.extractedIdentifier ?? '—'}</td>
                  <td className="sf-cell-wrap">
                    <span className={`badge ${statusBadgeClass(t.status)}`}>{t.status ?? '—'}</span>
                    {t.slaBreached && <span className="badge bg-danger ms-1">Breached</span>}
                  </td>
                  <td className="sf-col-wide">{formatConfidence(t.aiConfidence)}</td>
                  <td>
                    {t.assigneeUserId ? (
                      <span className="sf-assignee" title={t.assigneeEmail ?? undefined}>
                        <span className={`sf-avatar-sm ${t.assigneeUserId === myId ? 'is-me' : ''}`}>
                          {t.assigneeUserId === myId ? 'You'.slice(0, 2).toUpperCase() : initials(t.assigneeEmail)}
                        </span>
                        <span className="text-truncate">{t.assigneeUserId === myId ? 'You' : (t.assigneeEmail ?? 'Assigned')}</span>
                      </span>
                    ) : (
                      <span className="text-muted">Unassigned</span>
                    )}
                  </td>
                  <td title={formatDateTime(t.slaDeadlineAt)}>{formatDateTimeShort(t.slaDeadlineAt)}</td>
                  <td title={formatDateTime(t.createdAt)}>{formatDateTimeShort(t.createdAt)}</td>
                  <td className="text-end">
                    {!t.assigneeUserId && (
                      <button
                        type="button"
                        className="btn btn-outline-primary btn-sm"
                        disabled={acting === t.id}
                        onClick={() => void claim(t.id)}
                      >
                        Claim
                      </button>
                    )}
                    {t.assigneeUserId === myId && (
                      <button
                        type="button"
                        className="btn btn-outline-secondary btn-sm"
                        disabled={acting === t.id}
                        onClick={() => void release(t.id)}
                      >
                        Release
                      </button>
                    )}
                  </td>
                </tr>
              ))}
              {!loading && data?.content.length === 0 && (
                <tr><td colSpan={COLUMNS.length + 1} className="text-center text-muted py-5">No tickets</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {loading && <div className="text-muted mt-2">Loading…</div>}

      {data && data.totalPages > 1 && (
        <nav className="d-flex justify-content-between align-items-center mt-3">
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
