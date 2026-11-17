import { useCallback, useMemo, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import { useApi } from '../api/useApi.ts'
import { usePageData } from '../api/usePageData.ts'
import { useTicketStream } from '../api/useTicketStream.ts'
import type { Page, TicketStats, TicketSummary } from '../types.ts'
import { groupTickets, queueIds } from '../util/queue.ts'
import type { TicketQueue } from '../util/queue.ts'
import { useNow } from '../util/useNow.ts'
import { errorMessage } from '../util/errors.ts'
import { QUEUES, activeQueueOf, listRequest, listTitle, readListQuery } from './tickets/listQuery.ts'
import Pager from './tickets/Pager.tsx'
import SummaryChips from './tickets/SummaryChips.tsx'
import TicketFilters from './tickets/TicketFilters.tsx'
import TicketRow from './tickets/TicketRow.tsx'
import type { Assignment } from './tickets/TicketRow.tsx'

// A mail-style list grouped by SLA urgency. The server still decides the
// order (escalated first, then newest — or the operator's explicit sort); the page only buckets each
// row into Overdue / Due today / Later / Done, keeping server order inside a bucket. Grouping is per
// page (50 rows), which is what the operator is working through anyway.

export default function TicketsListPage() {
  const api = useApi()
  const auth = useAuth()
  const myId = auth.user?.profile.sub
  const location = useLocation()
  const [params, setParams] = useSearchParams()

  const [notice, setNotice] = useState<string | null>(null)
  const [reprocessing, setReprocessing] = useState(false)
  const [acting, setActing] = useState<number | null>(null)
  const now = useNow()

  // Memoised so the React Compiler can treat the query as frozen once read (it is passed on below).
  const query = useMemo(() => readListQuery(params), [params])
  const activeQueue = activeQueueOf(params, query)
  const { sort, dir, status, assignee, view, page } = query

  const load = useCallback(
    () => api.get<Page<TicketSummary>>(listRequest({ sort, dir, status, assignee, view, page })),
    [api, sort, dir, status, assignee, view, page],
  )
  const { data, error, setError, loading, reload } = usePageData(load, 'Failed to load tickets')

  // The summary strip is best-effort: a failed count never blocks the list, the last known counts stay.
  const loadStats = useCallback(() => api.get<TicketStats>('/api/tickets/summary'), [api])
  const { data: stats, reload: reloadStats } = usePageData(loadStats, 'Failed to load the summary')

  // Live updates: the backend pushes a ticket-changed event per tenant; we re-fetch the current query
  // and the summary counts. useTicketStream keeps the latest callback in a ref, so a filter change
  // never reconnects the stream.
  const live = useTicketStream(() => {
    void reload()
    void reloadStats()
  })

  const update = (next: Record<string, string>) => {
    const merged = new URLSearchParams(params)
    Object.entries(next).forEach(([k, v]) => (v ? merged.set(k, v) : merged.delete(k)))
    setParams(merged)
  }

  // Buckets in display order; the concatenation is the queue the ticket page walks with Send and next.
  const groups = useMemo(() => groupTickets(data?.content ?? [], now), [data, now])
  const back = location.pathname + location.search
  const queue: TicketQueue = useMemo(() => ({ ids: queueIds(groups), back }), [groups, back])

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
      await reload()
    } catch (e) {
      setError(errorMessage(e, 'Reprocess failed'))
    } finally {
      setReprocessing(false)
    }
  }, [api, reload, setError])

  // Shared-queue assignment: self-claim an unassigned ticket or release one of your
  // own. Assigning to another operator is a tenant-admin action, done from the ticket page.
  const assignment = async (id: number, action: Assignment) => {
    setActing(id)
    setError(null)
    try {
      await api.post(`/api/tickets/${id}/${action}`, action === 'assign' ? {} : undefined)
      await reload()
    } catch (e) {
      setError(errorMessage(e, action === 'assign' ? 'Claim failed' : 'Release failed'))
    } finally {
      setActing(null)
    }
  }

  return (
    <div className="sf-ws">
      <div className="sf-pagehead">
        <div>
          <div className="d-flex align-items-center gap-2">
            <h1 className="h4 mb-0">{listTitle(activeQueue, query)}</h1>
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
            className={`sf-tab${activeQueue?.key === q.key ? ' is-active' : ''}`}
            aria-current={activeQueue?.key === q.key ? 'page' : undefined}
          >
            {q.label}
          </Link>
        ))}
      </nav>

      <SummaryChips view={view} stats={stats} />

      <TicketFilters query={query} total={data ? data.totalElements : null} onChange={update} />

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
            {g.rows.map(({ t, sla }) => (
              <TicketRow
                key={t.id}
                t={t}
                sla={sla}
                queue={queue}
                myId={myId}
                busy={acting === t.id}
                onAssignment={(id, action) => void assignment(id, action)}
              />
            ))}
          </div>
        </section>
      ))}

      {!loading && data?.content.length === 0 && (
        <div className="sf-list text-center text-muted py-5">Nothing here — the queue is clear.</div>
      )}
      {loading && !data && <div className="text-muted mt-2">Loading…</div>}

      {data && <Pager data={data} page={page} onPage={(next) => update({ page: String(next) })} />}
    </div>
  )
}
