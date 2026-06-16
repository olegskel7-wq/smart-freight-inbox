import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useApi } from '../api/useApi.ts'
import { useTicketStream } from '../api/useTicketStream.ts'
import type { Page, TicketSummary } from '../types.ts'
import { TICKET_STATUSES } from '../types.ts'
import { formatConfidence, formatDateTime, statusBadgeClass } from '../util/format.ts'

const COLUMNS: { key: string; label: string; sortable: boolean }[] = [
  { key: 'id', label: 'ID', sortable: true },
  { key: 'senderEmail', label: 'From', sortable: true },
  { key: 'subject', label: 'Subject', sortable: true },
  { key: 'ticketType', label: 'Type', sortable: true },
  { key: 'extractedIdentifier', label: 'Identifier', sortable: true },
  { key: 'status', label: 'Status', sortable: true },
  { key: 'aiConfidence', label: 'Conf.', sortable: true },
  { key: 'slaDeadlineAt', label: 'SLA', sortable: true },
  { key: 'createdAt', label: 'Received', sortable: true },
]

export default function TicketsListPage() {
  const api = useApi()
  const [params, setParams] = useSearchParams()

  const sort = params.get('sort') ?? 'createdAt'
  const dir = params.get('dir') ?? 'desc'
  const status = params.get('status') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0'))

  const [data, setData] = useState<Page<TicketSummary> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const qs = new URLSearchParams({ sort, dir, page: String(page) })
      if (status) qs.set('status', status)
      const result = await api.get<Page<TicketSummary>>(`/api/tickets?${qs.toString()}`)
      setData(result)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load tickets')
    } finally {
      setLoading(false)
    }
  }, [api, sort, dir, status, page])

  useEffect(() => {
    void load()
  }, [load])

  // Live updates (Phase 3.2): the backend pushes a ticket-changed event per tenant; we re-fetch the
  // current query so the list always reflects the active filter/sort/page. A ref keeps the stream
  // subscription stable across filter changes (no reconnect just because `load` was recreated).
  const loadRef = useRef(load)
  useEffect(() => {
    loadRef.current = load
  }, [load])
  const onLiveEvent = useCallback(() => void loadRef.current(), [])
  const live = useTicketStream(onLiveEvent)

  const update = (next: Record<string, string>) => {
    const merged = new URLSearchParams(params)
    Object.entries(next).forEach(([k, v]) => (v ? merged.set(k, v) : merged.delete(k)))
    setParams(merged)
  }

  const toggleSort = (key: string) => {
    const nextDir = sort === key && dir === 'desc' ? 'asc' : 'desc'
    update({ sort: key, dir: nextDir, page: '0' })
  }

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <div className="d-flex align-items-center gap-2">
          <h1 className="h4 mb-0">Tickets</h1>
          <span
            className={`badge rounded-pill ${live ? 'bg-success' : 'bg-secondary'}`}
            title={live ? 'Live updates connected' : 'Live updates disconnected'}
          >
            {live ? '● Live' : '○ Offline'}
          </span>
        </div>
        <div className="d-flex align-items-center gap-2">
          <label className="text-muted small mb-0">Status</label>
          <select
            className="form-select form-select-sm"
            style={{ width: 'auto' }}
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '0' })}
          >
            <option value="">All</option>
            {TICKET_STATUSES.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </div>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}

      <div className="table-responsive">
        <table className="table table-hover table-sm align-middle">
          <thead>
            <tr>
              {COLUMNS.map((c) => (
                <th
                  key={c.key}
                  role={c.sortable ? 'button' : undefined}
                  onClick={c.sortable ? () => toggleSort(c.key) : undefined}
                  className="user-select-none"
                >
                  {c.label}
                  {sort === c.key && <span className="ms-1">{dir === 'asc' ? '▲' : '▼'}</span>}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {data?.content.map((t) => (
              <tr key={t.id}>
                <td><Link to={`/tickets/${t.id}`}>{t.id}</Link></td>
                <td>{t.senderEmail ?? '—'}</td>
                <td className="text-truncate" style={{ maxWidth: 320 }}>
                  <Link to={`/tickets/${t.id}`}>{t.subject || '(no subject)'}</Link>
                </td>
                <td>{t.ticketType ?? '—'}</td>
                <td>{t.extractedIdentifier ?? '—'}</td>
                <td>
                  <span className={`badge ${statusBadgeClass(t.status)}`}>{t.status ?? '—'}</span>
                  {t.slaBreached && <span className="badge bg-danger ms-1">Breached</span>}
                </td>
                <td>{formatConfidence(t.aiConfidence)}</td>
                <td>{formatDateTime(t.slaDeadlineAt)}</td>
                <td>{formatDateTime(t.createdAt)}</td>
              </tr>
            ))}
            {!loading && data?.content.length === 0 && (
              <tr><td colSpan={COLUMNS.length} className="text-center text-muted py-4">No tickets</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {loading && <div className="text-muted">Loading…</div>}

      {data && data.totalPages > 1 && (
        <nav className="d-flex justify-content-between align-items-center">
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
