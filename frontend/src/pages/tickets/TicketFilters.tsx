import { TICKET_STATUSES } from '../../types.ts'
import { statusLabel } from '../../util/labels.ts'
import { SORTS } from './listQuery.ts'
import type { ListQuery } from './listQuery.ts'

/**
 * The ticket count and the Assignee / Status / Sort selects. While the URL names a drill-down view
 * the filters show All. Picking All in both filters lands on the All queue rather than the Inbox;
 * every change goes back to the first page.
 */
export default function TicketFilters({ query, total, onChange }: {
  query: ListQuery
  total: number | null
  onChange: (next: Record<string, string>) => void
}) {
  const { sort, dir, status, assignee, viewInUrl } = query
  return (
    <div className="sf-filters">
      {total !== null && <span className="sf-count">{total} tickets</span>}
      <span className="ms-auto" />
      <label className="text-muted small mb-0" htmlFor="f-assignee">Assignee</label>
      <select
        id="f-assignee"
        className="form-select form-select-sm"
        value={viewInUrl ? 'all' : assignee}
        onChange={(e) => onChange({ assignee: e.target.value === 'all' ? '' : e.target.value, view: e.target.value === 'all' && !status ? 'all' : '', page: '0' })}
      >
        <option value="all">All</option>
        <option value="mine">Mine</option>
        <option value="unassigned">Unassigned</option>
      </select>
      <label className="text-muted small mb-0" htmlFor="f-status">Status</label>
      <select
        id="f-status"
        className="form-select form-select-sm"
        value={viewInUrl ? '' : status}
        onChange={(e) => onChange({ status: e.target.value, view: !e.target.value && assignee === 'all' ? 'all' : '', page: '0' })}
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
          onChange({ sort: s ?? '', dir: d ?? '', page: '0' })
        }}
      >
        {SORTS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
      </select>
    </div>
  )
}
